package main

import (
	"context"
	"fmt"
)

func (c *Client) pushBatches(ctx context.Context, root Root, session string, files []SourceNode,
	publish func(context.Context, []SourceNode) error, progress func(string, string) error) error {
	return parallelBatches(ctx, root, files, func(ctx context.Context) (batchWorker, func(), error) {
		target, err := c.directTarget(ctx, session)
		if err != nil {
			return nil, nil, err
		}
		return func(ctx context.Context, batch []SourceNode) error {
			nodes := make([]SourceNode, len(batch))
			for i, n := range batch {
				fileCtx := withSourceFile(ctx, n.Path)
				var err error
				nodes[i], err = hashSourceFile(fileCtx, root.Path, n)
				sourceProgress(fileCtx).done()
				if err != nil {
					return err
				}
			}
			if err := publish(ctx, nodes); err != nil {
				return err
			}
			var tickets []directTicket
			if err := c.request(ctx, "POST", "/agent/v1/push/direct-prepare-batch", map[string]any{"sessionId": session, "entries": nodes}, &tickets, true); err != nil {
				return err
			}
			if len(tickets) != len(nodes) {
				return fmt.Errorf("incomplete batch upload tickets")
			}
			var receipts []SourceNode
			for i, n := range nodes {
				if err := ctx.Err(); err != nil {
					return err
				}
				if tickets[i].Ready {
					continue
				}
				fileCtx := withSourceFile(ctx, n.Path)
				if err := progress("UPLOADING_B", n.Path); err != nil {
					return err
				}
				if err := c.writeDirectFile(fileCtx, target, root, n, tickets[i], progress); err != nil {
					return err
				}
				sourceProgress(fileCtx).done()
				receipts = append(receipts, n)
			}
			if len(receipts) > 0 {
				var acknowledged []directTicket
				if err := c.request(ctx, "POST", "/agent/v1/push/direct-complete-batch", map[string]any{"sessionId": session, "entries": receipts}, &acknowledged, true); err != nil {
					return err
				}
				if len(acknowledged) != len(receipts) {
					return fmt.Errorf("incomplete batch verification receipt")
				}
				for i, ticket := range acknowledged {
					if !ticket.Ready {
						return fmt.Errorf("batch file was not verified: %s", receipts[i].Path)
					}
				}
				c.uploadFiles.Add(int64(len(receipts)))
			}
			return nil
		}, func() { _ = target.close() }, nil
	})
}

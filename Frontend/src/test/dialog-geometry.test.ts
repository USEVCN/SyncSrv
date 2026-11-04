import { describe, expect, it } from 'vitest';
import { fitDialog, moveDialog, resizeDialog } from '../shared/dialogGeometry';

const viewport = { width: 1280, height: 720 };
const start = { left: 200, top: 100, width: 600, height: 400 };
describe('LE dialog gestures', () => {
  it('keeps every edge reachable during resize', () => {
    expect(resizeDialog(start, 'se', { x: 10000, y: 10000 }, viewport)).toEqual({ left: 200, top: 100, width: 1068, height: 608 });
    expect(resizeDialog(start, 'nw', { x: -10000, y: -10000 }, viewport)).toEqual({ left: 12, top: 12, width: 788, height: 488 });
  });
  it('preserves the opposite edge and minimum size', () => {
    const resized = resizeDialog(start, 'nw', { x: 10000, y: 10000 }, viewport);
    expect(resized).toEqual({ left: 440, top: 280, width: 360, height: 220 });
  });
  it('moves a small dialog without enlarging it', () => {
    expect(moveDialog({ left: 400, top: 200, width: 240, height: 150 }, { x: 3000, y: -3000 }, viewport))
      .toEqual({ left: 1028, top: 12, width: 240, height: 150 });
  });
  it('accepts real DOMRect values whose dimensions are prototype getters', () => {
    expect(moveDialog(new DOMRect(200, 100, 600, 400), { x: 120, y: 80 }, viewport))
      .toEqual({ left: 320, top: 180, width: 600, height: 400 });
  });
  it('fits resized dialogs into a narrower viewport', () => {
    expect(fitDialog(start, { width: 320, height: 360 })).toEqual({ left: 12, top: 12, width: 296, height: 336 });
  });
});

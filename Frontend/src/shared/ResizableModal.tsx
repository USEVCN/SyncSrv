import { App, Modal as AntModal, type ModalFuncProps, type ModalProps } from 'antd';
import { useCallback, useEffect, useImperativeHandle, useLayoutEffect, useMemo, useRef, type Ref, type ReactNode, type PointerEvent as ReactPointerEvent } from 'react';
import { fitDialog, moveDialog, resizeDialog, resizeDirections, type DialogRect, type ResizeDirection } from './dialogGeometry';

const cursors = { n: 'ns-resize', e: 'ew-resize', s: 'ns-resize', w: 'ew-resize', ne: 'nesw-resize', nw: 'nwse-resize', se: 'nwse-resize', sw: 'nesw-resize' };
const interactive = 'button,a,input,textarea,select,label,[role="button"],[role="tab"],[role="checkbox"],[role="switch"],[role="combobox"],[contenteditable]:not([contenteditable="false"]),[tabindex]:not([tabindex="-1"]),[data-dialog-no-drag]';
const viewport = () => ({ width: document.documentElement.clientWidth || window.innerWidth, height: document.documentElement.clientHeight || window.innerHeight });
type Gesture = { cleanup: () => void };

function DialogFrame({ children, open = true, gestureRef: outerGesture }: { children: ReactNode; open?: boolean; gestureRef?: Ref<{finish: () => void}> }) {
  const frameRef = useRef<HTMLDivElement>(null);
  const dialogRef = useRef<HTMLElement | null>(null);
  const rectRef = useRef<DialogRect | null>(null);
  const gestureRef = useRef<Gesture | null>(null);
  const finish = useCallback(() => {
    const current = gestureRef.current;
    gestureRef.current = null;
    current?.cleanup();
  }, []);
  useImperativeHandle(outerGesture, () => ({finish}), [finish]);
  const apply = useCallback((rect: DialogRect) => {
    const dialog = dialogRef.current;
    if (!dialog) return;
    rectRef.current = rect;
    dialog.classList.add('app-resizable-dialog', 'app-dialog-resized');
    Object.assign(dialog.style, { position: 'fixed', boxSizing: 'border-box', left: `${rect.left}px`, top: `${rect.top}px`,
      width: `${rect.width}px`, height: `${rect.height}px`, maxWidth: 'none', maxHeight: 'none', margin: '0' });
  }, []);
  const syncRadius = useCallback(() => {
    const surface = frameRef.current?.querySelector('.ant-modal-container');
    if (surface) frameRef.current?.style.setProperty('--app-dialog-corner-radius', getComputedStyle(surface).borderBottomRightRadius);
  }, []);
  const start = useCallback((event: ReactPointerEvent<HTMLElement>, direction: ResizeDirection | 'move') => {
    if (event.button !== 0 || (event.pointerType && event.pointerType !== 'mouse')
      || (typeof matchMedia === 'function' && !matchMedia('(hover: hover) and (pointer: fine)').matches)) return;
    const dialog = frameRef.current?.closest<HTMLElement>('.ant-modal');
    if (!dialog) return;
    finish();
    event.preventDefault(); event.stopPropagation();
    dialogRef.current = dialog;
    const moving = direction === 'move';
    const limits = { minHeight: parseFloat(getComputedStyle(dialog).getPropertyValue('--app-dialog-min-height')) || undefined };
    const initial = dialog.getBoundingClientRect();
    const rect = moving ? moveDialog(initial, {}, viewport()) : fitDialog(initial, viewport(), limits);
    const point = { x: event.clientX, y: event.clientY };
    const pointerId = event.pointerId;
    const handle = event.currentTarget;
    if (!moving) apply(rect);
    document.body.classList.add(moving ? 'app-dialog-is-moving' : 'app-dialog-is-resizing');
    document.body.style.setProperty('--app-dialog-resize-cursor', moving ? 'move' : cursors[direction]);
    const onMove = (next: PointerEvent) => {
      if (next.pointerId !== pointerId) return;
      next.preventDefault();
      const delta = { x: next.clientX - point.x, y: next.clientY - point.y };
      if (moving && Math.abs(delta.x) + Math.abs(delta.y) < 3) return;
      apply(moving ? moveDialog(rect, delta, viewport()) : resizeDialog(rect, direction, delta, viewport(), limits));
    };
    const onEnd = (next: PointerEvent) => { if (next.pointerId === pointerId) finish(); };
    const cleanup = () => {
      window.removeEventListener('pointermove', onMove);
      window.removeEventListener('pointerup', onEnd);
      window.removeEventListener('pointercancel', onEnd);
      window.removeEventListener('blur', finish);
      handle.removeEventListener('lostpointercapture', onEnd);
      if (handle.hasPointerCapture?.(pointerId)) handle.releasePointerCapture(pointerId);
      document.body.classList.remove('app-dialog-is-moving', 'app-dialog-is-resizing');
      document.body.style.removeProperty('--app-dialog-resize-cursor');
    };
    gestureRef.current = { cleanup };
    handle.setPointerCapture?.(pointerId);
    window.addEventListener('pointermove', onMove, { passive: false });
    window.addEventListener('pointerup', onEnd);
    window.addEventListener('pointercancel', onEnd);
    window.addEventListener('blur', finish);
    handle.addEventListener('lostpointercapture', onEnd);
  }, [apply, finish]);
  useLayoutEffect(() => {
    const dialog = frameRef.current?.closest<HTMLElement>('.ant-modal');
    if (!dialog) return;
    dialogRef.current = dialog;
    dialog.classList.add('app-resizable-dialog');
    if (rectRef.current) apply(rectRef.current);
  });
  useLayoutEffect(() => {
    const dialog = frameRef.current?.closest<HTMLElement>('.ant-modal');
    if (!dialog) return;
    syncRadius();
    return () => { dialog.classList.remove('app-resizable-dialog', 'app-dialog-resized'); dialogRef.current = null; };
  }, [syncRadius]);
  useEffect(() => { if (!open) finish(); }, [finish, open]);
  useEffect(() => {
    const resize = () => { finish(); syncRadius(); if (rectRef.current) apply(moveDialog(rectRef.current, {}, viewport())); };
    window.addEventListener('resize', resize);
    return () => { window.removeEventListener('resize', resize); finish(); };
  }, [apply, finish, syncRadius]);
  return <div ref={frameRef} className="app-resizable-dialog-frame" onPointerDown={event => {
    const target = event.target instanceof Element ? event.target : null;
    const title = target?.closest('.ant-modal-header,.ant-modal-confirm-title');
    if (title && !target?.closest(interactive) && title.closest('.ant-modal') === frameRef.current?.closest('.ant-modal')) start(event, 'move');
  }}>
    {children}
    {resizeDirections.map(direction => <span key={direction} aria-hidden="true" className="app-dialog-resize-handle"
      data-dialog-resize-handle={direction} onPointerDown={event => start(event, direction)} />)}
  </div>;
}

export function ResizableModal({ modalRender, open, ...props }: ModalProps) {
  const gesture = useRef<{finish: () => void}>(null);
  useEffect(() => { if (!open) gesture.current?.finish(); }, [open]);
  const render = useCallback((node: ReactNode) => <DialogFrame open={open} gestureRef={gesture}>{modalRender ? modalRender(node) : node}</DialogFrame>, [modalRender, open]);
  return <AntModal {...props} open={open} modalRender={render} />;
}
const resizableConfig = (config: ModalFuncProps): ModalFuncProps => ({ ...config,
  modalRender: node => <DialogFrame>{config.modalRender ? config.modalRender(node) : node}</DialogFrame> });

/** Keep Ant Design's modal context, promise controls, focus and confirmation behavior. */
export function useResizableApp() {
  const app = App.useApp();
  const modal = useMemo(() => ({ ...app.modal,
    confirm: (config: ModalFuncProps) => app.modal.confirm(resizableConfig(config)),
    info: (config: ModalFuncProps) => app.modal.info(resizableConfig(config)),
    success: (config: ModalFuncProps) => app.modal.success(resizableConfig(config)),
    error: (config: ModalFuncProps) => app.modal.error(resizableConfig(config)),
    warning: (config: ModalFuncProps) => app.modal.warning(resizableConfig(config)),
  }), [app.modal]);
  return { ...app, modal };
}

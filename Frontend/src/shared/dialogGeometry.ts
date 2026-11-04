// Same bounds and gestures as LabelEditor's desktop dialogs.
export const resizeDirections = ['n', 'e', 's', 'w', 'ne', 'nw', 'se', 'sw'] as const;
export type ResizeDirection = typeof resizeDirections[number];
export type DialogRect = { left: number; top: number; width: number; height: number };
type Viewport = { width: number; height: number };
type Delta = { x?: number; y?: number };
type Limits = { padding?: number; minWidth?: number; minHeight?: number };
const clamp = (value: number, min: number, max: number) => Math.min(Math.max(value, min), Math.max(min, max));
function bounds(viewport: Viewport, limits: Limits = {}) {
  const padding = Math.max(0, limits.padding ?? 12);
  const width = Math.max(0, viewport.width - padding * 2);
  const height = Math.max(0, viewport.height - padding * 2);
  return { padding, width, height, minWidth: Math.min(limits.minWidth ?? 360, width), minHeight: Math.min(limits.minHeight ?? 220, height) };
}
export function fitDialog(rect: DialogRect, viewport: Viewport, limits?: Limits): DialogRect {
  const b = bounds(viewport, limits);
  const width = clamp(rect.width, b.minWidth, b.width);
  const height = clamp(rect.height, b.minHeight, b.height);
  return { width, height, left: clamp(rect.left, b.padding, viewport.width - b.padding - width),
    top: clamp(rect.top, b.padding, viewport.height - b.padding - height) };
}
export function moveDialog(rect: DialogRect, delta: Delta, viewport: Viewport): DialogRect {
  // DOMRect dimensions are prototype getters, so object spread would discard them.
  return fitDialog({ width: rect.width, height: rect.height, left: rect.left + (delta.x || 0), top: rect.top + (delta.y || 0) }, viewport, { minWidth: 0, minHeight: 0 });
}
export function resizeDialog(rect: DialogRect, direction: ResizeDirection, delta: Delta, viewport: Viewport, limits?: Limits): DialogRect {
  const b = bounds(viewport, limits);
  let { left, top } = rect;
  let right = left + rect.width;
  let bottom = top + rect.height;
  if (direction.includes('w')) left = clamp(left + (delta.x || 0), b.padding, right - b.minWidth);
  if (direction.includes('e')) right = clamp(right + (delta.x || 0), left + b.minWidth, viewport.width - b.padding);
  if (direction.includes('n')) top = clamp(top + (delta.y || 0), b.padding, bottom - b.minHeight);
  if (direction.includes('s')) bottom = clamp(bottom + (delta.y || 0), top + b.minHeight, viewport.height - b.padding);
  return { left, top, width: Math.max(b.minWidth, right - left), height: Math.max(b.minHeight, bottom - top) };
}

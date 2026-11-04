import { Button, Checkbox, Empty, Input, Popover, Space, Table, type TableProps } from 'antd';
import { FilterOutlined, ReloadOutlined, SettingOutlined } from '@ant-design/icons';
import { isValidElement, useEffect, useMemo, useRef, useState, type PointerEvent, type ReactNode } from 'react';
import type { ColumnType, ColumnsType } from 'antd/es/table';
import { statusLabels } from './format';

type Preferences = { hidden: string[]; widths: Record<string, number> };
const emptyPreferences = (): Preferences => ({ hidden: [], widths: {} });
function readPreferences(key: string): Preferences {
  try {
    const value = JSON.parse(localStorage.getItem(key) || 'null');
    if (value && Array.isArray(value.hidden) && value.hidden.every((item: unknown) => typeof item === 'string') && value.widths && typeof value.widths === 'object') {
      return { hidden: value.hidden, widths: Object.fromEntries(Object.entries(value.widths)
        .filter((entry): entry is [string, number] => typeof entry[1] === 'number' && Number.isFinite(entry[1]) && entry[1] >= 88 && entry[1] <= 1600)) };
    }
  } catch { /* Local preferences are optional. */ }
  return emptyPreferences();
}
const columnKey = <T extends object>(column: ColumnType<T>, index: number) => String(column.key ?? column.dataIndex ?? index);
function renderedText(node: unknown): string {
  if (typeof node === 'string' || typeof node === 'number') return String(node);
  if (Array.isArray(node)) return node.map(renderedText).join(' ');
  if (isValidElement<{children?: ReactNode; value?: string}>(node))
    return renderedText(node.props.children) || statusLabels[node.props.value || ''] || '';
  if (node && typeof node === 'object' && 'children' in node) return renderedText(node.children);
  return '';
}
function FilterContent({ label, value, apply }: { label: string; value: string; apply: (value: string) => void }) {
  const [text, setText] = useState(value);
  return <div className="data-list-filter-panel">
    <Input aria-label={`筛选${label}`} value={text} onChange={event => setText(event.target.value)}
      onPressEnter={() => apply(text)} allowClear placeholder={`输入${label}`} />
    <small className="muted">筛选当前已加载的数据</small>
    <Space><Button size="small" aria-label="重置筛选" onClick={() => { setText(''); apply(''); }}>重置</Button>
      <Button size="small" aria-label="确定筛选" type="primary" onClick={() => apply(text)}>确定</Button></Space>
  </div>;
}

/** LE management-table presentation; retains each caller's data and pagination contract. */
export function DataTable<T extends object>({ preferenceKey, configurable, ...props }: TableProps<T> & { preferenceKey?: string; configurable?: boolean }) {
  // Compact read-only detail tables share the surface, without mounting an unused editor per column.
  const interactiveColumns = configurable ?? props.size !== 'small';
  const baseColumns = props.columns || [];
  const signature = baseColumns.map((column, index) => `${columnKey(column, index)}:${typeof column.title === 'string' ? column.title : ''}`).join('|');
  const storageKey = `syncsrv.table.ui.${preferenceKey || signature}`;
  const [saved, setSaved] = useState(() => ({ key: storageKey, value: readPreferences(storageKey) }));
  const preferences = saved.key === storageKey ? saved.value : readPreferences(storageKey);
  const [filters, setFilters] = useState<Record<string, string>>({});
  const [filterOpen, setFilterOpen] = useState('');
  const [settingsOpen, setSettingsOpen] = useState(false);
  const gesture = useRef<(() => void) | null>(null);
  useEffect(() => () => gesture.current?.(), []);
  const save = (next: Preferences) => {
    setSaved({ key: storageKey, value: next });
    try { localStorage.setItem(storageKey, JSON.stringify(next)); } catch { /* Storage may be disabled. */ }
  };
  const resize = (event: PointerEvent<HTMLElement>, key: string, current: number) => {
    if (event.button !== 0) return;
    event.preventDefault(); event.stopPropagation();
    gesture.current?.();
    const handle = event.currentTarget;
    const startX = event.clientX;
    const pointerId = event.pointerId;
    const initial = { ...preferences, widths: { ...preferences.widths } };
    const move = (next: globalThis.PointerEvent) => {
      if (next.pointerId !== pointerId) return;
      const width = Math.round(Math.min(1600, Math.max(88, current + next.clientX - startX)));
      setSaved({ key: storageKey, value: { ...initial, widths: { ...initial.widths, [key]: width } } });
      initial.widths[key] = width;
    };
    const finish = () => {
      window.removeEventListener('pointermove', move);
      window.removeEventListener('pointerup', end);
      window.removeEventListener('pointercancel', end);
      window.removeEventListener('blur', finish);
      handle.removeEventListener('lostpointercapture', end);
      if (handle.hasPointerCapture?.(pointerId)) handle.releasePointerCapture(pointerId);
      try { localStorage.setItem(storageKey, JSON.stringify(initial)); } catch { /* Optional preference. */ }
      gesture.current = null;
    };
    const end = (next: globalThis.PointerEvent) => { if (next.pointerId === pointerId) finish(); };
    gesture.current = finish;
    handle.setPointerCapture?.(pointerId);
    window.addEventListener('pointermove', move);
    window.addEventListener('pointerup', end);
    window.addEventListener('pointercancel', end);
    window.addEventListener('blur', finish);
    handle.addEventListener('lostpointercapture', end);
  };
  const visible = baseColumns.filter((column, index) => !preferences.hidden.includes(columnKey(column, index)));
  // Corrupt or outdated preferences must never produce an empty table.
  const shown = visible.length ? visible : baseColumns;
  const columnSettings = <div className="data-list-columns-panel" role="group" aria-label="显示列">
    {baseColumns.map((column, index) => {
      const key = columnKey(column, index);
      const checked = shown.includes(column);
      return <Checkbox key={key} checked={checked} disabled={checked && shown.length === 1} onChange={event => save({ ...preferences,
        hidden: event.target.checked ? preferences.hidden.filter(value => value !== key) : [...preferences.hidden, key] })}>
        {typeof column.title === 'string' ? column.title : `第 ${index + 1} 列`}
      </Checkbox>;
    })}
    <Button size="small" icon={<ReloadOutlined />} onClick={() => save(emptyPreferences())}>恢复默认列</Button>
  </div>;
  const columns: ColumnsType<T> = !interactiveColumns ? baseColumns : shown.map((original, visibleIndex) => {
    const key = columnKey(original, baseColumns.indexOf(original));
    if ('children' in original) return original;
    const title = original.title;
    const label = typeof title === 'string' ? title : '';
    const filterable = interactiveColumns && label && original.dataIndex && !original.filters && !original.filterDropdown;
    const heading = (content: ReactNode) => <div className="data-list-column-title">
      <span className="data-list-column-label">{content}</span>
      {filterable && <Popover trigger="click" open={filterOpen === key} onOpenChange={open => setFilterOpen(open ? key : '')}
        placement="bottomRight" getPopupContainer={trigger => trigger.closest<HTMLElement>('.data-list-table') || trigger.parentElement!}
        content={<FilterContent key={`${key}:${filters[key] || ''}`} label={label} value={filters[key] || ''} apply={value => {
          setFilters(previous => ({ ...previous, [key]: value.trim().toLocaleLowerCase() })); setFilterOpen('');
          if (props.pagination && props.pagination.onChange) props.pagination.onChange(1, props.pagination.pageSize || 10);
        }} />}>
        <Button className={`data-list-filter${filters[key] ? ' is-active' : ''}`} type="text" aria-label={`筛选${label}`}
          aria-pressed={!!filters[key]} size="small" icon={<FilterOutlined />} onClick={event => event.stopPropagation()} />
      </Popover>}
      {interactiveColumns && visibleIndex === shown.length - 1 && baseColumns.length > 1 && <Popover trigger="click" placement="bottomRight"
        open={settingsOpen} onOpenChange={setSettingsOpen} content={columnSettings}>
        <Button className="data-list-column-settings" type="text" aria-label="列设置" size="small" icon={<SettingOutlined />} onClick={event => event.stopPropagation()} />
      </Popover>}
      {visibleIndex < shown.length - 1 && <span aria-hidden="true" className="data-list-header-divider" />}
      {interactiveColumns && <span className="data-list-resize-handle" role="separator" aria-label={`调整${label || '列'}宽度`} aria-orientation="vertical"
        tabIndex={0} aria-valuemin={88} aria-valuemax={1600} aria-valuenow={preferences.widths[key] || (typeof original.width === 'number' ? original.width : 180)}
        onPointerDown={event => resize(event, key, event.currentTarget.closest('th')?.getBoundingClientRect().width || 180)}
        onKeyDown={event => {
          if (!['ArrowLeft', 'ArrowRight'].includes(event.key)) return;
          event.preventDefault(); event.stopPropagation();
          const width = preferences.widths[key] || event.currentTarget.closest('th')?.getBoundingClientRect().width || 180;
          save({ ...preferences, widths: { ...preferences.widths, [key]: Math.min(1600, Math.max(88, width + (event.key === 'ArrowRight' ? 16 : -16))) } });
        }} />}
    </div>;
    return { ...original, key, width: preferences.widths[key] || original.width,
      title: typeof title === 'function' ? context => heading(title(context)) : heading(title) };
  });
  const source = props.dataSource;
  const dataSource = useMemo(() => !Object.values(filters).some(Boolean) ? source : source?.filter((row, rowIndex) => baseColumns.every((column, index) => {
    if ('children' in column) return true;
    const filter = filters[columnKey(column, index)];
    if (!filter || !column.dataIndex) return true;
    const path = Array.isArray(column.dataIndex) ? column.dataIndex : [column.dataIndex];
    let value: unknown = row;
    for (const part of path) value = value && typeof value === 'object' ? (value as Record<string, unknown>)[String(part)] : undefined;
    if (String(value ?? '').toLocaleLowerCase().includes(filter)) return true;
    // Match labels and formatted dates/sizes visible to the user as well as raw values.
    return !!column.render && renderedText(column.render(value, row, rowIndex)).toLocaleLowerCase().includes(filter);
  })), [source, baseColumns, filters]);
  return <Table<T> size="large" scroll={{ x: 'max-content' }}
    pagination={{ pageSize: 10, showSizeChanger: false, showTotal: total => `共 ${total} 条` }}
    locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无记录" /> }}
    {...props} columns={columns} dataSource={dataSource} className={`data-list-table ${props.className || ''}`} />;
}

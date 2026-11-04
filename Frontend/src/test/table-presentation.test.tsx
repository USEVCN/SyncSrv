import { fireEvent, render, screen, within } from '@testing-library/react';
import { beforeEach, describe, expect, it } from 'vitest';
import userEvent from '@testing-library/user-event';
import { DataTable } from '../shared/DataTable';

const columns = [{ title: '名称', dataIndex: 'name' }, { title: '状态', dataIndex: 'state' }];
const rows = [{ id: 'one', name: 'alpha', state: 'ready' }, { id: 'two', name: 'beta', state: 'paused' }];
const table = () => <DataTable preferenceKey="test" rowKey="id" columns={columns} dataSource={rows} pagination={false} />;
describe('LE table presentation', () => {
  beforeEach(() => localStorage.clear());
  it('filters loaded rows without changing the supplied dataset', async () => {
    const user = userEvent.setup();
    render(table());
    await user.click(screen.getByRole('button', { name: '筛选名称' }));
    const field = await screen.findByRole('textbox', { name: '筛选名称' });
    fireEvent.change(field, { target: { value: 'alpha' } });
    await user.click(screen.getByRole('button', { name: '确定筛选' }));
    expect(screen.getByText('alpha')).toBeInTheDocument();
    expect(screen.queryByText('beta')).not.toBeInTheDocument();
    expect(rows).toHaveLength(2);
  });
  it('saves column selection and can restore all columns', async () => {
    const user = userEvent.setup();
    const mounted = render(table());
    await user.click(screen.getByRole('button', { name: '列设置' }));
    await user.click(await screen.findByRole('checkbox', { name: '状态' }));
    expect(screen.queryByText('ready')).not.toBeInTheDocument();
    mounted.unmount();
    render(table());
    expect(screen.queryByText('ready')).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: '列设置' }));
    await user.click(await screen.findByRole('button', { name: /恢复默认列/ }));
    expect(screen.getByText('ready')).toBeInTheDocument();
  });
  it('supports keyboard column resizing and retains the preference', () => {
    const mounted = render(table());
    fireEvent.keyDown(screen.getByRole('separator', { name: '调整名称宽度' }), { key: 'ArrowRight' });
    const width = screen.getByRole('separator', { name: '调整名称宽度' }).getAttribute('aria-valuenow');
    expect(Number(width)).toBeGreaterThan(88);
    mounted.unmount();
    render(table());
    expect(within(screen.getByRole('table')).getByRole('separator', { name: '调整名称宽度' })).toHaveAttribute('aria-valuenow', width);
  });
  it('matches the displayed label of a formatted column', async () => {
    const user = userEvent.setup();
    render(<DataTable rowKey="id" pagination={false} dataSource={rows} columns={[
      { title: '状态', dataIndex: 'state', render: value => <span>{value === 'ready' ? '就绪' : '已暂停'}</span> },
    ]} />);
    await user.click(screen.getByRole('button', { name: '筛选状态' }));
    fireEvent.change(await screen.findByRole('textbox', { name: '筛选状态' }), { target: { value: '就绪' } });
    await user.click(screen.getByRole('button', { name: '确定筛选' }));
    expect(screen.getByText('就绪')).toBeInTheDocument();
    expect(screen.queryByText('已暂停')).not.toBeInTheDocument();
  });
});

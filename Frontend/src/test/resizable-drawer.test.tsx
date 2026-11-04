import { fireEvent, render, screen } from "@testing-library/react";
import { beforeEach, expect, it } from "vitest";
import { ResizableDrawer } from "../shared/ResizableDrawer";
const width = () => (document.querySelector(".ant-drawer-content-wrapper") as HTMLElement).style.width;
const drag = (from:number, to:number) => {
  fireEvent.mouseDown(document.querySelector(".ant-drawer-resizable-dragger")!, {clientX:from});
  fireEvent.mouseMove(document, {clientX:to});
  fireEvent.mouseUp(document);
};
beforeEach(() => {
  localStorage.clear();
  Object.defineProperty(window, "innerWidth", {value:1440,writable:true});
});
it("resizes from the left edge, remembers the width and restores it after reopening", () => {
  const view = render(<ResizableDrawer open storageKey="report" initialWidth={900}>Report</ResizableDrawer>);
  expect(width()).toBe("900px");
  drag(540,340);
  expect(width()).toBe("1100px");
  expect(localStorage.getItem("syncsrv.drawer.width.report")).toBe("1100");
  view.unmount();
  render(<ResizableDrawer open storageKey="report" initialWidth={900}>Report</ResizableDrawer>);
  expect(width()).toBe("1100px");
});
it("clamps extreme dragging and fits a small screen without overwriting the preferred size", () => {
  render(<ResizableDrawer open storageKey="report" initialWidth={900}>Report</ResizableDrawer>);
  drag(540,2000); expect(width()).toBe("320px");
  drag(960,-2000); expect(width()).toBe("1416px");
  window.innerWidth=390; fireEvent(window,new Event("resize"));
  expect(width()).toBe("390px");
  expect(document.querySelector(".ant-drawer-resizable-dragger")).toBeNull();
  window.innerWidth=1440; fireEvent(window,new Event("resize"));
  expect(width()).toBe("1416px");
});

it("supports LE keyboard resizing, bounds and persisted size without a mouse",()=>{
  render(<ResizableDrawer open storageKey="report" initialWidth={900}>Report</ResizableDrawer>);
  const handle=screen.getByRole("separator",{name:"调整侧栏尺寸"});
  expect(handle).toHaveAttribute("tabindex","0");
  expect(handle).toHaveAttribute("aria-valuemin","320");
  fireEvent.keyDown(handle,{key:"ArrowLeft"});expect(width()).toBe("916px");
  expect(handle).toHaveAttribute("aria-valuenow","916");
  fireEvent.keyDown(handle,{key:"ArrowRight",shiftKey:true});expect(width()).toBe("852px");
  fireEvent.keyDown(handle,{key:"Home"});expect(width()).toBe("320px");
  fireEvent.keyDown(handle,{key:"ArrowRight"});expect(width()).toBe("320px");
  fireEvent.keyDown(handle,{key:"End"});expect(width()).toBe("1416px");
  expect(localStorage.getItem("syncsrv.drawer.width.report")).toBe("1416");
});

it("releases native document dragging when the window loses focus",()=>{
  render(<ResizableDrawer open storageKey="report" initialWidth={900}>Report</ResizableDrawer>);
  fireEvent.mouseDown(document.querySelector(".ant-drawer-resizable-dragger")!,{clientX:540});
  fireEvent.mouseMove(document,{clientX:340});expect(width()).toBe("1100px");
  fireEvent(window,new Event("blur"));fireEvent.mouseMove(document,{clientX:100});
  expect(width()).toBe("1100px");
  expect(localStorage.getItem("syncsrv.drawer.width.report")).toBe("1100");
});

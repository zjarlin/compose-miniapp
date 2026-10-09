#!/usr/bin/env python3
"""独立校验美团示例产物，不依赖转译器内部状态。"""

import json
import re
import sys
from pathlib import Path


out = Path(sys.argv[1] if len(sys.argv) > 1 else "out/miniapp-meituan")
errors = []


def err(message: str) -> None:
    errors.append(message)


required_pages = {
    "/pages/home/home",
    "/pages/store/store",
    "/pages/cart/cart",
    "/pages/orders/orders",
    "/pages/mine/mine",
}

app = json.loads((out / "app.json").read_text(encoding="utf-8"))
pages = set(app.get("pages", []))
if pages != required_pages:
    err(f"页面集合不匹配：{sorted(pages)}")

tab_paths = [item.get("pagePath") for item in app.get("tabBar", {}).get("list", [])]
if tab_paths != ["/pages/home/home", "/pages/orders/orders", "/pages/mine/mine"]:
    err(f"tabBar 不符合预期：{tab_paths}")

for page in required_pages:
    name = page.split("/")[-1]
    directory = out / "pages" / name
    for ext in ("wxml", "wxss", "js", "json"):
        if not (directory / f"{name}.{ext}").exists():
            err(f"{page} 缺少 {name}.{ext}")
    wxml = (directory / f"{name}.wxml").read_text(encoding="utf-8")
    if "{{item." not in wxml and name in {"home", "store", "cart", "orders"}:
        err(f"{page} 未发现动态列表绑定")

logic = (out / "logic" / "logic.js").read_text(encoding="utf-8")
for token in ("wx.requestPayment", "/api/orders", "mock"):
    if token not in logic:
        err(f"logic.js 缺少支付链路标记：{token}")

for asset in ("food-hotpot.webp", "food-burger.webp", "food-noodles.webp", "food-drink.webp", "avatar.webp"):
    if not (out / "assets" / asset).exists():
        err(f"缺少本地资源：{asset}")

wxss = "\n".join((out / "pages" / page.split("/")[-1] / f"{page.split('/')[-1]}.wxss").read_text(encoding="utf-8") for page in required_pages)
if "display: flex" not in wxss:
    err("WXSS 未生成 flex 布局")
if "border-radius" not in wxss:
    err("WXSS 未生成圆角样式")

if errors:
    print("美团示例校验失败：")
    for item in errors:
        print("  -", item)
    sys.exit(1)

print(f"美团示例校验通过：{len(pages)} 页 / {len(tab_paths)} 个 tab / 支付逻辑与资源齐全")

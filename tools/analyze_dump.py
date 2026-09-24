# -*- coding: utf-8 -*-
"""Analyze a uiautomator dump XML: EditText, scrollables, and text nodes."""
import sys
import xml.etree.ElementTree as ET

path = sys.argv[1]
W = 1264  # device width, bounds are absolute
tree = ET.parse(path)
root = tree.getroot()

def nodes(el):
    yield el
    for c in el:
        yield from nodes(c)

editboxes, scrollables, texts = [], [], []
for n in nodes(root):
    cls = n.get("class", "")
    rid = n.get("resource-id", "")
    text = (n.get("text") or "").strip()
    bounds = n.get("bounds", "")
    if cls == "android.widget.EditText":
        editboxes.append((rid, text, bounds, n.get("hint", "")))
    if cls in ("androidx.recyclerview.widget.RecyclerView", "android.widget.ListView",
               "android.widget.ScrollView", "androidx.core.widget.NestedScrollView") or \
       n.get("scrollable") == "true":
        scrollables.append((cls, rid, bounds))
    if text and cls == "android.widget.TextView":
        texts.append((rid, text, bounds))

print("== EditText ==")
for rid, text, b, hint in editboxes:
    print(f"  id={rid} text={text!r} hint={hint!r} bounds={b}")

print("== Scrollable ==")
for cls, rid, b in scrollables:
    print(f"  {cls} id={rid} bounds={b}")

print(f"== TextView with text: {len(texts)} ==")
for rid, text, b in texts:
    l, t, r, bt = [int(x) for x in b.strip("[]").split("][")[0:1][0].split(",")] if False else (0,0,0,0)
    import re
    m = re.findall(r"-?\d+", b)
    L, T, R, B = map(int, m)
    side = "R" if (L + R) / 2 > W / 2 else "L"
    w = R - L
    print(f"  [{side}] w={w:4d} x={L:4d}-{R:4d} y={T:4d}-{B:4d} id={rid.split('/')[-1] if rid else '(none)'} text={text[:40]!r}")

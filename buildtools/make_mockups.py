"""Render 1920x1080 mockups of the TV screens from the same tokens as Theme.java.

These are design references (not emulator screenshots) used in the README to review layout,
spacing and focus states without a device. Run: python3 buildtools/make_mockups.py
"""
from PIL import Image, ImageDraw, ImageFont

W, H = 1920, 1080
BG = (5, 7, 12)
PANEL = (11, 15, 23)
TILE = (14, 19, 28)
TILE_FOCUS = (20, 34, 46)
ACCENT = (51, 221, 187)
ACCENT_DIM = (27, 127, 114)
TEXT = (242, 245, 255)
SECOND = (138, 148, 168)
MUTED = (90, 100, 120)
BORDER = (30, 37, 50)
LIVE = (255, 77, 94)
WARN = (255, 176, 32)

BOLD = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
MED = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"
RECT = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"

def f(path, size):
    return ImageFont.truetype(path, size)

def rrect(d, box, r, fill=None, outline=None, width=1):
    d.rounded_rectangle(box, radius=r, fill=fill, outline=outline, width=width)

def video_gradient(img, box, seed=0):
    """Stand-in for a camera picture: dark studio-ish gradient with a subtle vignette."""
    x0, y0, x1, y1 = box
    w, h = x1 - x0, y1 - y0
    tile = Image.new("RGB", (w, h))
    px = tile.load()
    for y in range(h):
        for x in range(0, w, 4):
            t = (x / max(1, w)) * 0.7 + (y / max(1, h)) * 0.3
            base = int(18 + 26 * t)
            r = min(255, base + 12 + seed * 4)
            g = min(255, base + 4)
            b = min(255, base + 20 - seed * 3)
            for k in range(4):
                if x + k < w:
                    px[x + k, y] = (r, g, b)
    img.paste(tile, (x0, y0))

def nav(d, img, active, focused_idx=None):
    height = 66
    y0 = H - height
    d.rectangle([0, y0, W, H], fill=PANEL)
    d.line([0, y0, W, y0], fill=BORDER)
    labels = ["MAIN LIVE", "DINING", "EXTERNAL", "ALL"]
    widths = [210, 170, 190, 130]
    total = sum(widths) + 12 * 3
    x = (W - total) // 2 + 12
    for i, label in enumerate(labels):
        w = widths[i]
        focused = focused_idx == i
        if focused:
            rrect(d, [x, y0 + 8, x + w, H - 8], 6, fill=(26, 48, 58), outline=ACCENT, width=2)
        d.text((x + w // 2, y0 + 26), label, font=f(BOLD, 20), fill=(TEXT if focused else (ACCENT if i == active else SECOND)), anchor="mm")
        if i == active or focused:
            d.rectangle([x + 40, y0 + 46, x + w - 40, y0 + 49], fill=ACCENT)
        x += w + 12

def header(d, title, sub, badge="LIVE STUDIO"):
    d.text((28, 24), title, font=f(BOLD, 30), fill=TEXT, anchor="la")
    d.text((28, 66), sub, font=f(MED, 19), fill=SECOND, anchor="la")
    bw = d.textlength(badge, font=f(BOLD, 17)) + 24
    rrect(d, [W - 28 - bw, 34, W - 28, 74], 4, fill=(20, 40, 40), outline=ACCENT_DIM)
    d.text((W - 28 - bw / 2, 54), badge, font=f(BOLD, 17), fill=ACCENT, anchor="mm")

def live_dot(d, x, y, r=5):
    d.ellipse([x, y, x + 2 * r, y + 2 * r], fill=LIVE)

def screen_main():
    img = Image.new("RGB", (W, H), BG)
    d = ImageDraw.Draw(img)
    header(d, "MAIN LIVE", "Pinoy Big Brother · Collab Edition 3.0")
    cx0, cx1 = 28, W - 28
    cy0, cy1 = 112, H - 66 - 22
    box = (cx0 + 12, cy0 + 12, cx1 - 12, cy1 - 12)
    bw, bh = box[2] - box[0], box[3] - box[1]
    vw = bw
    vh = int(vw * 9 / 16)
    if vh > bh:
        vh = bh
        vw = int(vh * 16 / 9)
    vx = box[0] + (bw - vw) // 2
    vy = box[1] + (bh - vh) // 2
    rrect(d, [cx0, cy0, cx1, cy1], 10, fill=TILE)
    video_gradient(img, (vx, vy, vx + vw, vy + vh), seed=1)
    # scrim + label
    d.rectangle([vx, vy + vh - 130, vx + vw, vy + vh], fill=(0, 0, 0))
    live_dot(d, vx + 26, vy + vh - 92)
    d.text((vx + 44, vy + vh - 100), "PBB Main Live", font=f(BOLD, 34), fill=TEXT)
    d.text((vx + 46, vy + vh - 60), "Pinoy Big Brother · Collab Edition 3.0", font=f(MED, 20), fill=SECOND)
    # badge
    rrect(d, [vx + vw - 130, vy + 20, vx + vw - 20, vy + 52], 4, fill=(17, 22, 30))
    d.text((vx + vw - 75, vy + 36), "CAM 01", font=f(BOLD, 17), fill=TEXT, anchor="mm")
    nav(d, img, active=0)
    img.save("docs/img/screen-main-live.png")

def screen_all(focus=1, selected=None):
    img = Image.new("RGB", (W, H), BG)
    d = ImageDraw.Draw(img)
    d.text((28, 26), "ALL CAMERAS", font=f(BOLD, 30), fill=TEXT)
    d.text((28, 66), "5 cameras · 2×2 CCTV view", font=f(MED, 19), fill=SECOND)
    rrect(d, [W - 160, 34, W - 28, 74], 4, fill=(20, 24, 32), outline=BORDER)
    d.text((W - 94, 54), "PAGE 1 / 2", font=f(BOLD, 17), fill=SECOND, anchor="mm")
    labels = [("PBB Main Live", "Main camera"), ("Dining Area", "PBB All Access · camera 2"),
              ("External Den", "PBB All Access · camera 3"), ("All Access", "Full house")]
    grid_top, grid_bottom = 112, H - 66 - 16
    pad = 22
    gap = 12
    avail_w = W - 2 * pad
    avail_h = grid_bottom - grid_top
    cell_w = (avail_w - gap) // 2
    cell_h = (avail_h - gap) // 2
    for slot, (label, sub) in enumerate(labels):
        col, row = slot % 2, slot // 2
        cx = pad + col * (cell_w + gap)
        cy = grid_top + row * (cell_h + gap)
        vw = cell_w
        vh = int(vw * 9 / 16)
        if vh > cell_h:
            vh = cell_h
            vw = int(vh * 16 / 9)
        vx = cx + (cell_w - vw) // 2
        vy = cy + (cell_h - vh) // 2
        focused = slot == focus
        rrect(d, [vx - 3, vy - 3, vx + vw + 3, vy + vh + 3], 10, fill=TILE_FOCUS if focused else (0, 0, 0))
        video_gradient(img, (vx, vy, vx + vw, vy + vh), seed=slot + 1)
        d.rectangle([vx, vy + vh - 78, vx + vw, vy + vh], fill=(0, 0, 0))
        live_dot(d, vx + 16, vy + vh - 52, 4)
        d.text((vx + 32, vy + vh - 62), label, font=f(BOLD, 24), fill=TEXT)
        d.text((vx + 32, vy + vh - 32), sub, font=f(MED, 15), fill=SECOND)
        rrect(d, [vx + vw - 96, vy + 12, vx + vw - 12, vy + 40], 4, fill=(17, 22, 30))
        d.text((vx + vw - 54, vy + 26), "CAM %02d" % (slot + 1), font=f(BOLD, 14), fill=TEXT, anchor="mm")
        if focused:
            rrect(d, [vx - 3, vy - 3, vx + vw + 3, vy + vh + 3], 10, outline=ACCENT, width=4)
    nav(d, img, active=3)
    return img

def screen_controls():
    img = screen_all.__globals__  # keep linters quiet
    base = Image.new("RGB", (W, H), BG)
    d = ImageDraw.Draw(base)
    header(d, "MAIN LIVE", "Pinoy Big Brother · Collab Edition 3.0")
    video_gradient(base, (40, 124, W - 40, 124 + int((W - 80) * 9 / 16) - 0), seed=2)
    overlay = Image.new("RGBA", (W, H), (0, 0, 0, 150))
    base = Image.alpha_composite(base.convert("RGBA"), overlay).convert("RGB")
    d = ImageDraw.Draw(base)
    pw, ph = 900, 300
    px, py = (W - pw) // 2, (H - ph) // 2 - 30
    rrect(d, [px, py, px + pw, py + ph], 10, fill=(11, 15, 23), outline=BORDER, width=2)
    d.text((px + 40, py + 40), "PBB Main Live", font=f(BOLD, 34), fill=TEXT)
    d.text((px + 40, py + 88), "MPEG-DASH · ClearKey   ·   LICENSED", font=f(MED, 20), fill=SECOND)
    actions = ["Pause", "Next camera", "Mute", "Close"]
    x = px + 40
    for i, a in enumerate(actions):
        tw = d.textlength(a, font=f(BOLD, 22)) + 52
        focused = i == 0
        rrect(d, [x, py + 140, x + tw, py + 200], 6,
              fill=ACCENT if focused else (26, 30, 38),
              outline=ACCENT if focused else BORDER, width=2)
        d.text((x + tw / 2, py + 170), a, font=f(BOLD, 22),
               fill=(4, 19, 15) if focused else TEXT, anchor="mm")
        x += tw + 12
    d.text((W // 2, py + ph - 34), "OK  activate   ·   ◀ ▶  move   ·   BACK  close",
           font=f(BOLD, 16), fill=MUTED, anchor="mm")
    nav(d, base, active=0)
    base.save("docs/img/screen-controls.png")

def screen_licence():
    img = Image.new("RGB", (W, H), BG)
    d = ImageDraw.Draw(img)
    header(d, "DINING", "Dining feeds")
    cx0, cx1 = 28, W - 28
    cy0, cy1 = 118, H - 66 - 22
    rrect(d, [cx0, cy0, cx1, cy1], 10, fill=TILE)
    vw = cx1 - cx0 - 24
    vh = min(int(vw * 9 / 16), (cy1 - cy0) - 24)
    vx = cx0 + 12
    vy = cy0 + 12 + ((cy1 - cy0) - 24 - vh) // 2
    rrect(d, [vx, vy, vx + vw, vy + vh], 8, fill=(12, 16, 24))
    rrect(d, [vx, vy, vx + vw, vy + vh], 8, outline=BORDER, width=2)
    cxm, cym = vx + vw // 2, vy + vh // 2
    d.text((cxm, cym - 60), "Licence required", font=f(BOLD, 34), fill=WARN, anchor="mm")
    t1 = "This feed is ClearKey protected. Supply licence material you are"
    t2 = "authorised to use – keys are never bundled or bypassed."
    d.text((cxm, cym + 0), t1, font=f(MED, 21), fill=SECOND, anchor="mm")
    d.text((cxm, cym + 32), t2, font=f(MED, 21), fill=SECOND, anchor="mm")
    live_dot(d, vx + 26, vy + vh - 58)
    d.text((vx + 46, vy + vh - 70), "Dining Area", font=f(BOLD, 28), fill=TEXT)
    d.text((vx + 48, vy + vh - 32), "PBB All Access · camera 2", font=f(MED, 18), fill=SECOND)
    nav(d, img, active=1)
    img.save("docs/img/screen-licence.png")

if __name__ == "__main__":
    screen_main()
    screen_all(focus=1).save("docs/img/screen-all-grid.png")
    screen_all(focus=2).save("docs/img/screen-all-grid-focus.png")
    screen_controls()
    screen_licence()
    print("mockups written to docs/img/")

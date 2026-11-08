"""Record the Minecraft window to an animated GIF for the mod page (Windows).

Usage:
    python tools/record_gif.py                      # 30 s at 10 fps -> media/demo.gif
    python tools/record_gif.py --seconds 45 --speed 2 --width 640

Starts after a 3 second countdown, so you can click back into the game. Only the
Minecraft window is captured, nothing else on your screen. Needs Pillow (pip install pillow).
"""
import argparse
import ctypes
import ctypes.wintypes as wt
import os
import time

from PIL import Image, ImageGrab

user32 = ctypes.windll.user32
user32.SetProcessDPIAware()  # real pixel coordinates on scaled displays


def find_minecraft_window():
    found = []

    @ctypes.WINFUNCTYPE(ctypes.c_bool, wt.HWND, wt.LPARAM)
    def callback(hwnd, _):
        if not user32.IsWindowVisible(hwnd):
            return True
        length = user32.GetWindowTextLengthW(hwnd)
        title = ctypes.create_unicode_buffer(length + 1)
        user32.GetWindowTextW(hwnd, title, length + 1)
        if title.value.startswith("Minecraft"):
            found.append(hwnd)
        return True

    user32.EnumWindows(callback, 0)
    return found[0] if found else None


def client_box(hwnd):
    rect = wt.RECT()
    user32.GetClientRect(hwnd, ctypes.byref(rect))
    top_left = wt.POINT(0, 0)
    user32.ClientToScreen(hwnd, ctypes.byref(top_left))
    return (top_left.x, top_left.y, top_left.x + rect.right, top_left.y + rect.bottom)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--seconds", type=float, default=30, help="how long to record")
    parser.add_argument("--fps", type=float, default=10, help="frames captured per second")
    parser.add_argument("--speed", type=float, default=1, help="playback speed-up, e.g. 2 plays twice as fast")
    parser.add_argument("--width", type=int, default=720, help="output width in pixels")
    parser.add_argument("--out", default=os.path.join("media", "demo.gif"))
    args = parser.parse_args()

    hwnd = find_minecraft_window()
    if not hwnd:
        raise SystemExit("No Minecraft window found. Start the game first.")

    for n in (3, 2, 1):
        print(f"Recording in {n}...", flush=True)
        time.sleep(1)
    print(f"Recording {args.seconds:g} s. Go!", flush=True)

    frames = []
    interval = 1 / args.fps
    end = time.time() + args.seconds
    next_shot = time.time()
    while time.time() < end:
        box = client_box(hwnd)
        frame = ImageGrab.grab(bbox=box, all_screens=True)
        height = round(frame.height * args.width / frame.width)
        frames.append(frame.resize((args.width, height), Image.LANCZOS))
        next_shot += interval
        time.sleep(max(0, next_shot - time.time()))

    print(f"Captured {len(frames)} frames, encoding...", flush=True)
    # One shared palette keeps colours stable between frames and the file small.
    palette_source = frames[len(frames) // 2].quantize(colors=255, method=Image.Quantize.MEDIANCUT)
    gif_frames = [f.quantize(palette=palette_source, dither=Image.Dither.NONE) for f in frames]
    os.makedirs(os.path.dirname(args.out) or ".", exist_ok=True)
    gif_frames[0].save(args.out, save_all=True, append_images=gif_frames[1:], loop=0,
                       duration=round(1000 * interval / args.speed), optimize=True, disposal=1)
    print(f"Saved {args.out} ({os.path.getsize(args.out) / 1e6:.1f} MB)")


if __name__ == "__main__":
    main()

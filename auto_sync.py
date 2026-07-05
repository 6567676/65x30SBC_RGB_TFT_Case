import os
import time
import subprocess

WATCH_DIR = r"D:\zero_case"
DEBOUNCE = 1.0
CHECK_INTERVAL = 1.0

def get_snapshot():
    files = {}
    for root, dirs, names in os.walk(WATCH_DIR):
        dirs[:] = [d for d in dirs if d != ".git"]
        for name in names:
            path = os.path.join(root, name)
            try:
                files[path] = os.path.getmtime(path)
            except:
                pass
    return files

def git_push():
    subprocess.run(["git", "add", "."], cwd=WATCH_DIR, capture_output=True)
    result = subprocess.run(["git", "status", "--porcelain"], cwd=WATCH_DIR, capture_output=True, text=True)
    if result.stdout.strip():
        subprocess.run(["git", "commit", "-m", f"auto sync {time.strftime('%Y-%m-%d %H:%M:%S')}"], cwd=WATCH_DIR, capture_output=True)
        subprocess.run(["git", "push"], cwd=WATCH_DIR, capture_output=True)
        print(f"[{time.strftime('%H:%M:%S')}] 同步完成")

last_snapshot = get_snapshot()
pending = False
change_time = 0

while True:
    time.sleep(CHECK_INTERVAL)
    current_snapshot = get_snapshot()
    if current_snapshot != last_snapshot:
        last_snapshot = current_snapshot
        pending = True
        change_time = time.time()
    if pending and (time.time() - change_time) >= DEBOUNCE:
        git_push()
        pending = False
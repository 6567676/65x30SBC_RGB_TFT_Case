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
    subprocess.run(["git", "add", "."], cwd=WATCH_DIR)
    result = subprocess.run(["git", "diff", "--cached", "--quiet"], cwd=WATCH_DIR)
    if result.returncode == 0:
        return
    subprocess.run(["git", "commit", "-m", f"auto sync {time.strftime('%Y-%m-%d %H:%M:%S')}"], cwd=WATCH_DIR)
    r = subprocess.run(["git", "push"], cwd=WATCH_DIR, capture_output=True, text=True)
    if r.returncode == 0:
        print(f"[{time.strftime('%H:%M:%S')}] 同步完成")
    else:
        print(f"[{time.strftime('%H:%M:%S')}] 推送失败: {r.stderr}")

print("监听中 | 1Hz检测 | 1s防抖")
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
        print(f"[{time.strftime('%H:%M:%S')}] 检测到变化")
    if pending and (time.time() - change_time) >= DEBOUNCE:
        git_push()
        pending = False
import urllib.request
import json
import time

API = "http://127.0.0.1:25569"

def post(endpoint, data=None):
    url = f"{API}{endpoint}"
    req_data = json.dumps(data).encode("utf-8") if data else b"{}"
    req = urllib.request.Request(url, data=req_data, headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except Exception as e:
        print(f"Error calling {endpoint}: {e}")
        return None

def main():
    print("[1] Bringing client window forward...")
    post("/api/window/show")

    print("[2] Setting daytime and clear weather...")
    post("/api/chat", {"message": "/time set day"})
    post("/api/chat", {"message": "/weather sun"})
    post("/api/select_hotbar", {"slot": 1})
    post("/api/look", {"yaw": 0.0, "pitch": 5.0})
    time.sleep(0.5)

    print("[3] Starting RMB charge...")
    post("/api/use_item", {"hold": True})

    # While holding, capture orbiting ice crystals & helix vortex
    time.sleep(1.5)
    print("Capturing charge phase (orbiting crystals & frost mandala)...")
    post("/api/screenshot")

    time.sleep(1.8)
    print("Capturing high energy charge phase...")
    post("/api/screenshot")

    time.sleep(0.5)
    print("[4] Releasing RMB to cast...")
    post("/api/release_use_item")

    time.sleep(0.2)
    print("Capturing release burst...")
    post("/api/screenshot")

    time.sleep(0.8)
    print("Capturing impact...")
    post("/api/screenshot")

    print("Done!")

if __name__ == "__main__":
    main()

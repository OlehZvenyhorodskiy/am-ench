import urllib.request
import json
import time
import os

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

def get(endpoint):
    url = f"{API}{endpoint}"
    try:
        with urllib.request.urlopen(url) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except Exception as e:
        print(f"Error calling {endpoint}: {e}")
        return None

def main():
    print("[1] Bringing Minecraft window forward on desktop...")
    post("/api/window/show")

    print("[2] Selecting hotbar slot 1 (iceshtorm sword) and aiming...")
    post("/api/select_hotbar", {"slot": 1})
    post("/api/look", {"yaw": 0.0, "pitch": 5.0})
    post("/api/chat", {"message": "/kill @e[type=zombie,distance=..20]"})
    time.sleep(0.3)
    post("/api/chat", {"message": "/summon zombie 514.5 98.0 41.0 {NoAI:1b,CustomName:'\"Test Dummy\"'}"})
    time.sleep(0.5)

    print("[3] Starting RMB charge (iceshtorm)...")
    post("/api/use_item", {"hold": True})

    # Charge for 1.8s -> capture charging phase 1
    time.sleep(1.8)
    print("Capturing Charge Phase 1...")
    post("/api/screenshot")

    # Charge until 3.8s -> capture charging phase 2
    time.sleep(2.0)
    print("Capturing Charge Phase 2 (Accelerated Vortex & Multiple Orbiting Crystals)...")
    post("/api/screenshot")

    # Release at 4.2s to cast the storm
    time.sleep(0.5)
    print("[4] Releasing RMB -> CASTING ICESHTORM!")
    post("/api/release_use_item")

    # Capture cast shockwave and launch
    time.sleep(0.3)
    print("Capturing Cast Burst & Missile Launch...")
    post("/api/screenshot")

    # Capture missile impact & Frost Nova
    time.sleep(0.8)
    print("Capturing Frost Nova Shatter...")
    post("/api/screenshot")

    # Capture heavenly frost lightning
    time.sleep(1.2)
    print("Capturing Heavenly Ice Lightning Pillar...")
    post("/api/screenshot")

    time.sleep(1.0)
    print("Capturing Aftermath & Frozen Dummy...")
    post("/api/screenshot")

    print("Sequence completed successfully!")

if __name__ == "__main__":
    main()

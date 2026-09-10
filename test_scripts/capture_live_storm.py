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

    print("[2] Aiming at target zone...")
    post("/api/look", {"yaw": 0.0, "pitch": 5.0})
    post("/api/select_hotbar", {"slot": 1})

    print("[3] Clearing and summoning fresh dummy...")
    post("/api/chat", {"message": "/kill @e[type=zombie,distance=..25]"})
    time.sleep(0.3)
    post("/api/chat", {"message": "/summon zombie 514.5 98.0 42.0 {NoAI:1b,CustomName:'\"Frozen Target\"'}"})
    time.sleep(0.5)

    print("[4] Casting Iceshtorm (Power 5)...")
    post("/api/chat", {"message": "/aquaenchant cast iceshtorm 5 AquaTester"})

    # Rapid burst capture of cast & flight & impact & lightning
    time.sleep(0.08)
    print("Frame 1: Cast shockwave & launch...")
    post("/api/screenshot")

    time.sleep(0.35)
    print("Frame 2: Missiles flying with blizzard corkscrews...")
    post("/api/screenshot")

    time.sleep(0.55)
    print("Frame 3: Frost Nova shatter on collision...")
    post("/api/screenshot")

    time.sleep(0.5)
    print("Frame 4: Celestial frost lightning descent & thunder shockwave...")
    post("/api/screenshot")

    time.sleep(0.9)
    print("Frame 5: Deep freeze & aftermath...")
    post("/api/screenshot")

    print("Live sequence captured successfully!")

if __name__ == "__main__":
    main()

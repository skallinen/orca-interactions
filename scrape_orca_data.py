#!/usr/bin/env python3
"""
Scrape orca interaction data from the Cruising Association API.
Downloads report list + all detailed incident and uneventful passage reports.
Uses concurrent requests to handle the slow API.
Outputs to CSV and JSON files for analysis.
"""

import json
import csv
import os
import sys
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed

BASE_URL = "https://apinl.theca.org.uk/orcasurvey/v1"
HEADERS = {"caapi-clienttype": "websiteapp"}
OUTPUT_DIR = "orca_data"
MAX_WORKERS = 15  # Concurrent requests

def api_get(path, timeout=60):
    """Make a GET request to the CA orca survey API."""
    url = f"{BASE_URL}/{path}"
    req = urllib.request.Request(url, headers=HEADERS)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return json.loads(resp.read().decode())
    except Exception as e:
        return None

def flatten_response(response_data):
    """Flatten a report response dict from {field: {Q: ..., A: ...}} to {field: answer}."""
    flat = {}
    for key, val in response_data.items():
        if isinstance(val, dict) and "A" in val:
            flat[key] = val["A"].strip() if isinstance(val["A"], str) else val["A"]
        else:
            flat[key] = val
    return flat

def fetch_report(args):
    """Fetch a single report. Returns (report_id, summary, flat_data, report_type) or None."""
    report_id, summary, report_type = args
    endpoint = "incidentresponse" if report_type == "incident" else "uneventfulresponse"
    
    # Retry up to 3 times
    for attempt in range(3):
        detail = api_get(f"{endpoint}/{report_id}", timeout=120)
        if detail and detail.get("status") == "OK" and "response" in detail:
            flat = flatten_response(detail["response"])
            flat["report_id"] = report_id
            flat["serial"] = summary.get("serial", "")
            flat["summary_lat"] = summary.get("lat", "")
            flat["summary_long"] = summary.get("long", "")
            flat["summary_time"] = summary.get("time", "")
            flat["report_type"] = report_type
            return (report_id, flat)
    
    return (report_id, None)

def save_csv(records, filepath, priority_keys):
    """Save records to CSV with priority column ordering."""
    if not records:
        return
    
    all_keys = set()
    for r in records:
        all_keys.update(r.keys())
    
    remaining_keys = sorted(all_keys - set(priority_keys))
    fieldnames = [k for k in priority_keys if k in all_keys] + remaining_keys
    
    with open(filepath, "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=fieldnames, extrasaction="ignore")
        writer.writeheader()
        writer.writerows(records)

def main():
    os.makedirs(OUTPUT_DIR, exist_ok=True)

    # Step 1: Get report list
    print("Fetching report list...")
    data = api_get("reportlist?withdetails=true")
    if not data:
        print("Failed to fetch report list!")
        sys.exit(1)

    reports = data.get("reports", {})
    incidents = reports.get("incident", {})
    uneventful = reports.get("uneventful", {})

    print(f"Found {len(incidents)} incident reports and {len(uneventful)} uneventful passage reports")

    # Save raw report list
    with open(os.path.join(OUTPUT_DIR, "reportlist.json"), "w") as f:
        json.dump(data, f, indent=2)
    
    # Also save a quick CSV of just the report list (coordinates + times)
    summary_records = []
    for rid, s in incidents.items():
        summary_records.append({
            "report_id": rid, "type": "incident", "serial": s.get("serial",""),
            "time": s.get("time",""), "lat": s.get("lat",""), "long": s.get("long","")
        })
    for rid, s in uneventful.items():
        summary_records.append({
            "report_id": rid, "type": "uneventful", "serial": s.get("serial",""),
            "time": s.get("time",""), "lat": s.get("lat",""), "long": s.get("long","")
        })
    save_csv(summary_records, os.path.join(OUTPUT_DIR, "all_reports_summary.csv"),
             ["report_id", "type", "serial", "time", "lat", "long"])
    print(f"Saved {len(summary_records)} report summaries to {OUTPUT_DIR}/all_reports_summary.csv")

    # Step 2: Download all detailed reports concurrently
    tasks = []
    for rid, summary in incidents.items():
        tasks.append((rid, summary, "incident"))
    for rid, summary in uneventful.items():
        tasks.append((rid, summary, "uneventful"))

    print(f"\nDownloading {len(tasks)} detailed reports with {MAX_WORKERS} concurrent workers...")
    print("(This API is slow — expect ~5-10 minutes)")

    incident_details = []
    uneventful_details = []
    completed = 0
    failed = 0

    with ThreadPoolExecutor(max_workers=MAX_WORKERS) as executor:
        futures = {executor.submit(fetch_report, task): task for task in tasks}
        
        for future in as_completed(futures):
            completed += 1
            report_id, flat = future.result()
            
            if flat:
                if flat["report_type"] == "incident":
                    incident_details.append(flat)
                else:
                    uneventful_details.append(flat)
            else:
                failed += 1
            
            if completed % 25 == 0 or completed == len(tasks):
                print(f"  Progress: {completed}/{len(tasks)} ({failed} failed)")
                sys.stdout.flush()

    # Step 3: Save results
    all_details = incident_details + uneventful_details
    
    with open(os.path.join(OUTPUT_DIR, "all_reports_detailed.json"), "w") as f:
        json.dump(all_details, f, indent=2, ensure_ascii=False)
    print(f"\nSaved {len(all_details)} detailed reports to {OUTPUT_DIR}/all_reports_detailed.json")

    incident_priority = [
        "report_id", "serial", "report_type", "date_of_interaction", "time_of_interaction",
        "summary_lat", "summary_long", "summary_time",
        "lat_deg", "lat_min", "long_deg", "long_min",
        "boat_type", "boat_length", "motoring_or_sailing", "speed",
        "rudder", "autopilot", "antifoul_colour", "hull_topsides_colour",
        "depth", "distance_off_land", "sea_state", "wind_speed",
        "darkness_or_daylight", "cloud_cover",
        "damaged", "tow_required", "length_of_interaction",
        "behaviour_text_edited", "response_text_edited",
        "towing", "depth_gauge", "daystage", "moon", "tide"
    ]
    save_csv(incident_details, os.path.join(OUTPUT_DIR, "incident_reports.csv"), incident_priority)
    print(f"Saved {len(incident_details)} incident reports to {OUTPUT_DIR}/incident_reports.csv")

    uneventful_priority = [
        "report_id", "serial", "report_type", "summary_time",
        "summary_lat", "summary_long",
        "boat_type", "boat_length", "motoring_or_sailing", "speed",
        "rudder", "autopilot", "antifoul_colour", "hull_topsides_colour",
        "depth", "distance_off_land", "sea_state", "wind_speed",
    ]
    save_csv(uneventful_details, os.path.join(OUTPUT_DIR, "uneventful_reports.csv"), uneventful_priority)
    print(f"Saved {len(uneventful_details)} uneventful reports to {OUTPUT_DIR}/uneventful_reports.csv")

    # Summary
    print(f"\n{'='*60}")
    print(f"SCRAPING COMPLETE")
    print(f"{'='*60}")
    print(f"Incident reports:   {len(incident_details)}/{len(incidents)}")
    print(f"Uneventful reports: {len(uneventful_details)}/{len(uneventful)}")
    print(f"Failed:             {failed}")
    print(f"\nOutput files in '{OUTPUT_DIR}/':")
    for f in sorted(os.listdir(OUTPUT_DIR)):
        size = os.path.getsize(os.path.join(OUTPUT_DIR, f))
        print(f"  {f:40s} {size:>10,} bytes")

if __name__ == "__main__":
    main()

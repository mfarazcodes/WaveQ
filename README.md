# WaveQ 🌊🚨
> **An Android disaster-response app that keeps working when the network doesn't.**[cite: 2]

[![Smart India Hackathon 2026](https://img.shields.io/badge/SIH-2026-blue.svg)](https://sih.gov.in/)[cite: 2]
[![Platform](https://img.shields.io/badge/Platform-Android-green.svg)](https://developer.android.com)
[![Language](https://img.shields.io/badge/Language-Kotlin-purple.svg)](https://kotlinlang.org)[cite: 2]
[![UI Framework](https://img.shields.io/badge/UI-Jetpack%20Compose%20%7C%20Material%203-blueviolet.svg)](https://developer.android.com/jetpack/compose)[cite: 2]
[![License](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

---

## 📌 Problem Statement Overview
* **Problem Statement ID:** 26192[cite: 2]
* **Title:** Flash Flood Prediction System for Hilly Regions using Multi-Source Data[cite: 2]
* **Theme / Category:** Disaster Management | Software[cite: 2]
* **Team:** Ripple (Team ID: 112)[cite: 2]

In mountainous and hilly terrains, torrential rain causes sudden, violent flash floods within minutes. In these geographies, **telecom infrastructure (cell towers, fiber backhauls, and power grids) fails first**[cite: 2]. Traditional disaster alert systems rely on SMS or active cloud connections; when towers collapse, victims cannot call for help and rescue teams lose situational awareness[cite: 2].

**WaveQ** assumes complete cellular blackout as the baseline default[cite: 2]. It combines multi-source, explainable flood prediction with an infrastructure-less peer-to-peer (P2P) mesh network so distress beacons, hazard reports, and evacuation guidance keep moving hop-by-hop between devices[cite: 2].

---

## ⚡ Key Highlights & Architecture

### 1. Multi-Source Explainable Risk Engine
* **Hydrological & Meteorological Integration:** Ingests rainfall forecasts combined with upstream **river discharge data** ($m^3/s$) via keyless Open-Meteo APIs[cite: 2].
* **Transparent Attribution:** Computes risk deterministically via named, auditable indicators (runoff rates, saturation indices, rainfall volume) instead of uninterpretable machine-learning black boxes[cite: 2].
* **ESP8266 IoT Sensor Telemetry:** Integrates physical riverbank water level sensors running on ESP8266 microcontrollers directly into the app lifecycle[cite: 3].

### 2. Offline-First P2P Mesh Communication
* **Zero-Infrastructure Relaying:** Uses **Google Nearby Connections** (BLE + Wi-Fi Direct) to discover neighboring devices and route packets hop-by-hop without internet or cell service[cite: 2].
* **Encrypted Private Channels:** Employs **AES-GCM** encryption with **PBKDF2** key stretching for family/responder squads alongside an open, public emergency band[cite: 2].
* **Walkie-Talkie Over Mesh:** Records, caches, and serializes low-bitrate compressed voice notes to stream peer-to-peer between off-grid survivors[cite: 1, 3].
* **Deduplication & Anti-Storming:** Implements in-memory/disk caches (`AlertDedupCache`) and timestamp expiration (`AlertFreshness`) to stop packet looping across valley nodes[cite: 3].

### 3. Critical Alert Takeover & Audio Siren
* **Lock-Screen Override:** When catastrophic flash flood levels are breached, `CriticalAlertActivity` launches as a high-priority full-screen intent that wakes the screen and bypasses keyguards[cite: 1, 2, 3].
* **Hardware Audio Siren:** Bypasses silent and Do Not Disturb (DND) modes through `SirenPlayer` to deliver unmissable evacuation instructions[cite: 1, 2, 3].

### 4. Keyless & Swadeshi Design
* Uses **OSMDroid (OpenStreetMap)** for mapping, eliminating proprietary map API billing and enabling offline raster/vector tile caching[cite: 2].

---

## 🏗️ System Architecture

WaveQ operates on an **Offline-First Triangular Architecture** designed for zero-infrastructure resilience:


```text
  [ Open-Meteo Forecast & River Data ]     [ ESP8266 Riverbank IoT Sensor ]
                   |                                      |
                   +------------------+-------------------+
                                      |
                                      v
                   [ WaveQ Risk Engine & Surge Detector ]
                                      |
                                      v
                   [ Room SQLite Database (Local Cache) ]
                                      |
         +-------------------+--------+--------+-------------------+
         |                   |                 |                   |
         v                   v                 v                   v
   [ WorkManager ]   [ Nearby Mesh ]   [ Critical Siren ]   [ OSMDroid Map ]
    (Cloud Sync)       (P2P Relay)       (Lock Override)      (Offline Nav)

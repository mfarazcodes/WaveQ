# WaveQ 🚨

**WaveQ** is a modern, offline-resilient emergency reporting and disaster response platform built natively for Android using Jetpack Compose. Designed for citizens, first responders, and crisis operators, WaveQ bridges the gap between field incident reporting and administrative operations during emergency scenarios.

---

## 🌟 Key Features

* **Precision Incident Reporting**
  * High-accuracy GPS localization via Google Play Services Fused Location Provider.
  * Native reverse geocoding to resolve street, locality, and district addresses automatically.
  * Structured multi-hazard reporting categorized by disaster type (Flood, Fire, Hurricane, Earthquake) and severity level (Low, Medium, High, Critical, Evacuate).

* **Operator Command Center**
  * Interactive **Crisis Map** providing spatial visibility of active disaster clusters.
  * Real-time metrics tracking total incidents, verified reports, pending reviews, and average response times.
  * Dynamic, animated analytics powered by custom Jetpack Compose Canvas charts:
    * **Donut Charts:** Reports broken down by severity and status.
    * **Horizontal Bar Charts:** Incident distribution across disaster types.
    * **Sparkline Area Trend:** 24-hour chronological report volume with gradient shading.

* **Admin Management Panel**
  * Role-based access control with live member monitoring (`Administrator`, `Operator`, `Viewer`).
  * In-app user lifecycle operations: dynamically add, inspect, and remove team members.
  * Real-time system health checks and active incident counters.

* **Adaptive UI/UX & Session Management**
  * Ergonomic right-to-left modal navigation drawer for one-handed operation.
  * Context-aware session states reflecting authenticated operator credentials.
  * Clean, accessible Material 3 design tokens customized for high-contrast visibility in emergency conditions.

---

## 🛠 Tech Stack & Architecture

| Layer | Technologies |
| :--- | :--- |
| **Language** | Kotlin |
| **UI Framework** | Jetpack Compose (Material 3) |
| **Navigation** | Navigation Compose |
| **Location Services** | Google Play Services (`play-services-location`), Android Geocoder |
| **Visualizations** | Custom Compose `Canvas` drawing APIs |
| **Concurrency** | Kotlin Coroutines & `LaunchedEffect` |
| **Icons & Assets** | Extended Material Icons, Custom Vector Adaptive Assets |

---

## 📂 Project Structure

```text
app/src/main/java/com/waveq/app/
├── MainActivity.kt               # Main entry point and runtime permission hooks
├── navigation/
│   └── AppNavigation.kt          # Routes, NavHost, and modal navigation drawer
└── ui/
    ├── components/
    │   ├── AnalyticsCharts.kt    # Custom Canvas donut, bar, and

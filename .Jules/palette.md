# Palette's Journal

## 2026-07-31 - [Research Table UX Tooltips]
**Learning:** Custom UI gauges (like the Wind Meter and Research Progress Bar in the Research Table block) can feel static and obscure without explicit, interactive feedback. Displaying actual values, required thresholds, speed multipliers, and detailed progress percentages via localized multi-line tooltips significantly enhances player usability.
**Action:** Implement `isMouseAboveArea` on `ResearchTableScreen` to display interactive, multi-line, localized tooltips on both the Wind Meter and the Progress Bar.

## 2026-08-01 - [Interactive Screen Navigation & Tooltips]
**Learning:** Hardcoded button labels and static, non-reactive navigation bars reduce accessibility and clarity. Using translatable Components for all button labels, disabling the active tab's button (`.active = false`), and attaching localized descriptions via the modern 1.21 `Tooltip.create(...)` API provides clear, screen-reader-friendly interactive feedback.
**Action:** Always localize tab text/headers, disable currently active navigation controls, and add descriptive tooltips to custom screen tab buttons to guide the player's focus.

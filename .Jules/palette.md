# Palette's Journal

## 2026-07-31 - [Research Table UX Tooltips]
**Learning:** Custom UI gauges (like the Wind Meter and Research Progress Bar in the Research Table block) can feel static and obscure without explicit, interactive feedback. Displaying actual values, required thresholds, speed multipliers, and detailed progress percentages via localized multi-line tooltips significantly enhances player usability.
**Action:** Implement `isMouseAboveArea` on `ResearchTableScreen` to display interactive, multi-line, localized tooltips on both the Wind Meter and the Progress Bar.

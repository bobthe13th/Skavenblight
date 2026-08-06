# Palette's Journal

## 2026-07-31 - [Research Table UX Tooltips]
**Learning:** Custom UI gauges (like the Wind Meter and Research Progress Bar in the Research Table block) can feel static and obscure without explicit, interactive feedback. Displaying actual values, required thresholds, speed multipliers, and detailed progress percentages via localized multi-line tooltips significantly enhances player usability.
**Action:** Implement `isMouseAboveArea` on `ResearchTableScreen` to display interactive, multi-line, localized tooltips on both the Wind Meter and the Progress Bar.

## 2026-08-01 - [Warpstone Nexus Tab State & Accessibility]
**Learning:** Plain vanilla-style buttons used for screens with tabs/panels (like `NexusScreen`) can feel confusing and unresponsive when the active tab remains clickable. Disabling the active tab button provides instant visual feedback about the current state, simplifies screen readers' focus handling, and prevents unnecessary UI re-initialization. Furthermore, utilizing translatable components ensures full localization capability.
**Action:** Enable active state toggles (`button.active = (currentTab != Tab)`) on tab buttons, localize all hardcoded literal components, and add context-rich tooltips via the modern `Tooltip.create(...)` API.

# Project

## Purpose
Custom fork of grocy-android for personal household inventory and shopping automation.

## Main goals
- Fast barcode-based product handling.
- Reuse reliable product data automatically.
- Never guess uncertain values.
- Keep workflows simple and mobile-first.

## Quantity model
Packaging, content quantity and content unit are separate concepts.

Examples:
- Packaging: bottle, jar, can, pack, bag, carton, tray, cup, tube, piece.
- Content quantity: numeric value.
- Content unit: g, kg, ml, l, cl, piece.

Rules:
- Clearly detected values may be prefilled automatically.
- Uncertain values stay empty and require confirmation.
- Confirmed values are stored and reused later.
- The same assistant logic should be used during first product setup and later purchase/stock workflows.

## Product data
- Prefer reliable external product data such as OpenFoodFacts.
- Do not infer packaging, storage location, product group, minimum stock, store, price, or shelf life without reliable data.
- Allow fast manual selection for missing or uncertain fields.

## Workflow
Claude plans and reviews.
Codex is the primary implementer for coding, refactoring, debugging and tests.
Use the global model-routing rules.

## Development rule
Before changing behavior, inspect the existing implementation and preserve working upstream behavior unless the requested change requires otherwise.

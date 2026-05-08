# MediVault

MediVault is a production-style starter for a personal medical health report manager.

## Tech Stack

- Android: Kotlin, Jetpack Compose, Material 3, Navigation, Hilt, Room, WorkManager
- Backend: Node.js, Express, MongoDB (Mongoose), JWT auth, OCR + AI extraction pipeline
- AI/OCR: Tesseract + Gemini structured extraction
- Storage: Local disk in starter (replace with S3/GCS/Firebase Storage in production)

## Monorepo Structure

- `android/` - Mobile app source
- `backend/` - API server, OCR + AI extraction, reminders worker
- `docs/` - API notes and architecture references

## Quick Start

### Backend

1. `cd backend`
2. `npm install`
3. Copy `.env.example` to `.env` and fill values
4. `npm run dev`

Health check: `GET http://localhost:5000/health`

### Android

1. Open `android/` in Android Studio (Giraffe+)
2. Let Gradle sync
3. Set backend URL in `BuildConfig.API_BASE_URL` if needed
4. Run app on Android 8.0+

## Core Delivered Features

- Secure auth (email/phone/password + Google endpoint placeholder)
- Profile management (blood group, allergies, emergency contact)
- Report upload (PDF/image/scan) with OCR + AI extraction
- Timeline and AI trend summary endpoints
- Medication tracker with daily intake logging
- Appointments and reminders CRUD
- Compose-based modern app screens (dashboard, upload, timeline, meds, reminders, appointments, profile)
- Offline cache foundation via Room entities/DAO
- Dark mode-ready Material 3 UI

## Production Hardening Checklist

- Replace local uploads with encrypted cloud object storage
- Add refresh tokens + device/session management
- Add Firebase Cloud Messaging/APNs bridge for push reminders
- Add E2E encryption for report blobs and key management
- Add stronger OCR/AI confidence metrics and human review flow
- Add analytics/monitoring and test coverage expansion

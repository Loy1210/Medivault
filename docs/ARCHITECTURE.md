# Architecture Notes

## Android

- Compose-first UI with bottom navigation and modular screens.
- Hilt as DI container.
- Room for offline cache and local timeline snapshots.
- WorkManager for periodic reminder sync and medication check-ins.
- Retrofit for backend communication.

## Backend

- Express API with JWT auth guard.
- MongoDB models for users, reports, medications, appointments, reminders.
- OCR pipeline:
  1. PDF/image upload
  2. OCR text extraction (pdf-parse / Tesseract)
  3. LLM extraction to structured JSON
  4. Persist to `reports.extractedData`
- Reminder worker using cron each minute.

## Security

- Password hashing via bcrypt.
- JWT auth tokens with 7-day expiry.
- Sensitive file uploads should move to encrypted cloud object storage in production.
- Add audit logs + refresh token rotation before production launch.

# MediVault Backend API

Base URL: `http://localhost:5000/api`

## Auth

- `POST /auth/register` `{ name, email|phone, password }`
- `POST /auth/login` `{ emailOrPhone, password }`
- `POST /auth/google` `{ idToken }`

## Profile

- `GET /profile/me`
- `PUT /profile/me`

## Reports

- `POST /reports/upload` (multipart `file`, optional `fileType`)
- `GET /reports`
- `GET /reports/timeline`

## Medications

- `POST /medications`
- `GET /medications`
- `POST /medications/:id/log`

## Appointments

- `POST /appointments`
- `GET /appointments`

## Reminders

- `POST /reminders`
- `GET /reminders`

All routes except `/auth/*` and `/health` require `Authorization: Bearer <token>`.

// MongoDB indexing script
db.users.createIndex({ email: 1 }, { unique: true, sparse: true });
db.users.createIndex({ phone: 1 }, { unique: true, sparse: true });
db.reports.createIndex({ userId: 1, reportDate: -1 });
db.medications.createIndex({ userId: 1, createdAt: -1 });
db.appointments.createIndex({ userId: 1, dateTime: 1 });
db.reminders.createIndex({ userId: 1, scheduleAt: 1, enabled: 1 });

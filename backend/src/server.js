import "dotenv/config";
import express from "express";
import cors from "cors";
import mongoose from "mongoose";
import multer from "multer";
import bcrypt from "bcryptjs";
import jwt from "jsonwebtoken";
import pdfParse from "pdf-parse";
import Tesseract from "tesseract.js";
import cron from "node-cron";
import { OAuth2Client } from "google-auth-library";
import fs from "node:fs/promises";

const app = express();
app.use(cors());
app.use(express.json({ limit: "10mb" }));
app.use("/uploads", express.static("uploads"));
const upload = multer({ dest: "uploads/" });

await mongoose.connect(process.env.MONGO_URI);

const userSchema = new mongoose.Schema({
  name: String, email: { type: String, unique: true, sparse: true }, phone: { type: String, unique: true, sparse: true },
  passwordHash: String, age: Number, bloodGroup: String, allergies: [String],
  emergencyContact: { name: String, phone: String, relation: String }
}, { timestamps: true });
const reportSchema = new mongoose.Schema({
  userId: { type: mongoose.Schema.Types.ObjectId, ref: "User", required: true },
  fileType: String, storagePath: String, hospitalName: String, doctorName: String, reportDate: Date,
  extractedData: {
    tests: [{
      name: String,
      value: String,
      valueNumeric: Number,
      unit: String,
      referenceRange: String,
      flag: String
    }],
    medicines: [{ name: String, dosage: String, frequency: String }],
    summary: String
  }
}, { timestamps: true });
const medicationSchema = new mongoose.Schema({
  userId: { type: mongoose.Schema.Types.ObjectId, ref: "User", required: true },
  tabletName: String, dosage: String, frequency: String, foodTiming: String, startDate: Date, endDate: Date,
  dailyLogs: [{ date: Date, taken: Boolean }]
}, { timestamps: true });
const appointmentSchema = new mongoose.Schema({
  userId: { type: mongoose.Schema.Types.ObjectId, ref: "User", required: true },
  doctorName: String, hospital: String, dateTime: Date, notes: String
}, { timestamps: true });
const reminderSchema = new mongoose.Schema({
  userId: { type: mongoose.Schema.Types.ObjectId, ref: "User", required: true },
  title: String, type: String, scheduleAt: Date, recurringRule: String, enabled: { type: Boolean, default: true }
}, { timestamps: true });

const User = mongoose.model("User", userSchema);
const Report = mongoose.model("Report", reportSchema);
const Medication = mongoose.model("Medication", medicationSchema);
const Appointment = mongoose.model("Appointment", appointmentSchema);
const Reminder = mongoose.model("Reminder", reminderSchema);

const geminiApiKey = process.env.GEMINI_API_KEY;
const geminiModel = process.env.GEMINI_MODEL || "gemini-1.5-pro";
const googleClient = new OAuth2Client(process.env.GOOGLE_CLIENT_ID);

const tokenFor = (user) => jwt.sign({ sub: user._id, email: user.email, phone: user.phone }, process.env.JWT_SECRET, { expiresIn: "7d" });
const auth = (req, res, next) => {
  const token = req.headers.authorization?.replace("Bearer ", "");
  if (!token) return res.status(401).json({ message: "Unauthorized" });
  try { req.user = jwt.verify(token, process.env.JWT_SECRET); next(); } catch { res.status(401).json({ message: "Invalid token" }); }
};

const ocrText = async (path, mime) => {
  if (mime === "application/pdf") return (await pdfParse(await fs.readFile(path))).text;
  return (await Tesseract.recognize(path, "eng")).data.text;
};

const geminiGenerate = async ({ prompt, responseMimeType }) => {
  if (!geminiApiKey) return { disabled: true, text: "" };

  if (typeof fetch !== "function") {
    throw new Error("Global fetch is not available in this Node runtime.");
  }

  // Expected GEMINI_MODEL format: e.g. "gemini-1.5-pro" (NOT including a "models/" prefix).
  const url = `https://generativelanguage.googleapis.com/v1beta/models/${geminiModel}:generateContent?key=${encodeURIComponent(geminiApiKey)}`;
  const body = {
    contents: [{ role: "user", parts: [{ text: prompt }] }],
    generationConfig: {
      temperature: 0,
      ...(responseMimeType ? { responseMimeType } : {})
    }
  };

  const resp = await fetch(url, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body)
  });

  if (!resp.ok) {
    const errText = await resp.text().catch(() => "");
    throw new Error(`Gemini API error: HTTP ${resp.status} ${errText}`.trim());
  }

  const data = await resp.json();
  const text = data?.candidates?.[0]?.content?.parts?.[0]?.text;
  return { disabled: false, text: typeof text === "string" ? text : "" };
};

const tryParseJson = (value) => {
  if (!value) return {};
  const cleaned = value
    .trim()
    .replace(/^```json\s*/i, "")
    .replace(/^```/i, "")
    .replace(/```$/i, "");

  // If the model returned surrounding text, attempt to extract the first JSON object.
  const start = cleaned.indexOf("{");
  const end = cleaned.lastIndexOf("}");
  const candidate = start >= 0 && end > start ? cleaned.slice(start, end + 1) : cleaned;

  return JSON.parse(candidate);
};

const extractMedical = async (raw) => {
  if (!geminiApiKey) return { tests: [], medicines: [], summary: "OCR completed. AI disabled until GEMINI_API_KEY is set." };

  const prompt = `You are a medical document extractor.
Extract structured JSON from the patient's report text.

Return ONLY valid JSON (no markdown) matching this schema:
{
  "tests": [{ "name": string, "value": string, "referenceRange": string }],
  "medicines": [{ "name": string, "dosage": string, "frequency": string }],
  "doctorName": string|null,
  "hospitalName": string|null,
  "reportDate": string|null,
  "summary": string
}

Rules:
- If a field is not found, use null or [] as appropriate.
- reportDate should be ISO-8601 date string when possible.
- Use the exact value text from the report (including units if present).

Report text (may include OCR errors):
${raw.slice(0, 12000)}`;

  const { text } = await geminiGenerate({ prompt, responseMimeType: "application/json" });
  try {
    return tryParseJson(text);
  } catch {
    return { tests: [], medicines: [], summary: "AI response JSON parse failed. OCR completed." };
  }
};

const normalizeTest = (test) => {
  const value = `${test?.value ?? ""}`.trim();
  const numericMatch = value.match(/-?\d+(\.\d+)?/);
  return {
    name: `${test?.name ?? "Unknown test"}`.trim(),
    value,
    valueNumeric: numericMatch ? Number.parseFloat(numericMatch[0]) : undefined,
    unit: `${test?.unit ?? ""}`.trim(),
    referenceRange: `${test?.referenceRange ?? ""}`.trim(),
    flag: `${test?.flag ?? ""}`.trim()
  };
};

const normalizeMedicalData = (ai) => {
  const parsedDate = ai?.reportDate ? new Date(ai.reportDate) : undefined;
  return {
    doctorName: `${ai?.doctorName ?? ""}`.trim(),
    hospitalName: `${ai?.hospitalName ?? ""}`.trim(),
    reportDate: parsedDate && !Number.isNaN(parsedDate.getTime()) ? parsedDate : undefined,
    extractedData: {
      tests: Array.isArray(ai?.tests) ? ai.tests.map(normalizeTest).filter((t) => t.name) : [],
      medicines: Array.isArray(ai?.medicines)
        ? ai.medicines.map((m) => ({
          name: `${m?.name ?? ""}`.trim(),
          dosage: `${m?.dosage ?? ""}`.trim(),
          frequency: `${m?.frequency ?? ""}`.trim()
        })).filter((m) => m.name)
        : [],
      summary: `${ai?.summary ?? ""}`.trim()
    }
  };
};

app.get("/health", (_req, res) => res.json({ status: "ok", service: "MediVault API" }));

app.post("/api/auth/register", async (req, res) => {
  const { name, email, phone, password } = req.body;
  const exists = await User.findOne({ $or: [{ email }, { phone }] });
  if (exists) return res.status(409).json({ message: "User exists" });
  const user = await User.create({ name, email, phone, passwordHash: await bcrypt.hash(password, 10) });
  res.status(201).json({ token: tokenFor(user), user });
});

app.post("/api/auth/login", async (req, res) => {
  const { emailOrPhone, password } = req.body;
  const user = await User.findOne({ $or: [{ email: emailOrPhone }, { phone: emailOrPhone }] });
  if (!user || !user.passwordHash || !(await bcrypt.compare(password, user.passwordHash))) return res.status(401).json({ message: "Invalid credentials" });
  res.json({ token: tokenFor(user), user });
});

app.post("/api/auth/google", async (req, res) => {
  if (!process.env.GOOGLE_CLIENT_ID) {
    return res.status(500).json({ message: "GOOGLE_CLIENT_ID is not configured on server." });
  }
  const ticket = await googleClient.verifyIdToken({ idToken: req.body.idToken, audience: process.env.GOOGLE_CLIENT_ID });
  const payload = ticket.getPayload();
  let user = await User.findOne({ email: payload.email });
  if (!user) user = await User.create({ name: payload.name || "Google User", email: payload.email });
  res.json({ token: tokenFor(user), user });
});

app.get("/api/profile/me", auth, async (req, res) => res.json(await User.findById(req.user.sub).select("-passwordHash")));
app.put("/api/profile/me", auth, async (req, res) => res.json(await User.findByIdAndUpdate(req.user.sub, req.body, { new: true }).select("-passwordHash")));

app.post("/api/reports/upload", auth, upload.single("file"), async (req, res) => {
  if (!req.file) return res.status(400).json({ message: "File required" });
  const raw = await ocrText(req.file.path, req.file.mimetype);
  const ai = await extractMedical(raw);
  const normalized = normalizeMedicalData(ai);
  const report = await Report.create({
    userId: req.user.sub,
    fileType: req.body.fileType || "scan",
    storagePath: req.file.path,
    hospitalName: normalized.hospitalName,
    doctorName: normalized.doctorName,
    reportDate: normalized.reportDate,
    extractedData: normalized.extractedData
  });
  res.status(201).json(report);
});

app.get("/api/reports", auth, async (req, res) => res.json(await Report.find({ userId: req.user.sub }).sort({ reportDate: -1, createdAt: -1 })));

app.get("/api/reports/timeline", auth, async (req, res) => {
  const timeline = await Report.find({ userId: req.user.sub }).sort({ reportDate: 1 });
  if (!geminiApiKey) return res.json({ timeline, summary: "AI trend summary disabled until GEMINI_API_KEY is configured." });

  const prompt = `You are a patient-friendly medical assistant.
Based on the report timeline objects, generate a short trend summary for:
- Blood sugar (glucose/HbA1c if present)
- Cholesterol (LDL/HDL/Triglycerides if present)
- Blood pressure patterns (if present)

Requirements:
- Be clear and non-alarming.
- Do not provide diagnosis.
- Mention dates when values change.
- Keep it under 180 words.
- Output plain text with 3-6 short lines. 

Timeline JSON:
${JSON.stringify(timeline).slice(0, 12000)}`;

  const { text } = await geminiGenerate({ prompt });
  const summary = text || "Trend summary unavailable.";
  res.json({ timeline, summary });
});

app.post("/api/medications", auth, async (req, res) => res.status(201).json(await Medication.create({ ...req.body, userId: req.user.sub })));
app.get("/api/medications", auth, async (req, res) => res.json(await Medication.find({ userId: req.user.sub }).sort({ createdAt: -1 })));
app.post("/api/medications/:id/log", auth, async (req, res) => {
  const m = await Medication.findOne({ _id: req.params.id, userId: req.user.sub });
  if (!m) return res.status(404).json({ message: "Medication not found" });
  m.dailyLogs.push({ date: new Date(), taken: !!req.body.taken });
  await m.save();
  res.json(m);
});

app.post("/api/appointments", auth, async (req, res) => res.status(201).json(await Appointment.create({ ...req.body, userId: req.user.sub })));
app.get("/api/appointments", auth, async (req, res) => res.json(await Appointment.find({ userId: req.user.sub }).sort({ dateTime: 1 })));
app.post("/api/reminders", auth, async (req, res) => res.status(201).json(await Reminder.create({ ...req.body, userId: req.user.sub })));
app.get("/api/reminders", auth, async (req, res) => res.json(await Reminder.find({ userId: req.user.sub, enabled: true }).sort({ scheduleAt: 1 })));

cron.schedule("*/1 * * * *", async () => {
  const now = new Date();
  const due = await Reminder.find({ enabled: true, scheduleAt: { $gte: new Date(now.getTime() - 60_000), $lte: now } });
  due.forEach((r) => console.log(`[Reminder] ${r.type} ${r.title}`));
});

app.use((err, _req, res, _next) => {
  console.error(err);
  res.status(500).json({ message: err?.message || "Internal server error" });
});

app.listen(process.env.PORT || 5000, () => console.log(`MediVault API running on ${process.env.PORT || 5000}`));

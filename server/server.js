const express = require("express");
const sqlite3 = require("sqlite3").verbose();

const app = express();
const port = 3000;

// Middleware
app.use(express.urlencoded({ extended: true }));
app.use(express.json());

// Database setup
const db = new sqlite3.Database("users.db");

// Create table if not exists
db.serialize(() => {
  db.run(`
    CREATE TABLE IF NOT EXISTS users (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      username TEXT,
      password TEXT,
      salt TEXT
    )
  `);

  // password is password123
  db.run(`
    INSERT OR IGNORE INTO  users (username, password, salt)
    VALUES ('adminUser2', 'de4c58a4d8593482f30c68286327d39c4bf1feb7883cc3a3688e91c018e8c01a', '7/l5SRzaB0nQEiQGc8LEJw==')
  `);

  // IHateChoosingPasswords
  db.run(`
    INSERT OR IGNORE INTO  users (username, password, salt)
    VALUES ('someGuy', 'aa05263ff3ce8a90f466257a135697fa849aa462170f0c9207772414b49f76a6', 'SWjfvdGGGhTPDuSfstmC4w==')
  `);
});

// Import routes
const userRoutes = require("./routes/user");
const updateRoutes = require("./routes/updates")
const viewRoutes= require("./routes/view")

// Pass db into routes
app.use("/user", (req, res, next) => {
  req.db = db;
  next();
}, userRoutes);

app.use("/updates", (req, res, next) => {
  next();
}, updateRoutes);

app.use("/view", (req, res, next) => {
  next();
}, viewRoutes);

// Basic route
app.get("/", (req, res) => {
  res.send(`Server running successfully at http://localhost:${port}!`);
});

// Start server
app.listen(port, () => {
  console.log(`Server running at http://localhost:${port}`);
});
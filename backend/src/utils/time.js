// Timestamp helper to keep event ordering consistent across modules.
function nowMs() {
  return Date.now();
}

module.exports = { nowMs };

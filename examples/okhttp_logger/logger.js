var config = require("./config.js")

function toText(value) {
  if (value === null || value === undefined) return "null"

  try {
    return String(value)
  } catch (e) {
    return "<toString failed>"
  }
}

function debug(message) {
  xposed.d(config.TAG, toText(message))
}

function info(message) {
  xposed.i(config.TAG, toText(message))
}

function warn(message) {
  xposed.w(config.TAG, toText(message))
}

function error(message, throwable) {
  if (throwable) {
    xposed.e(config.TAG, toText(message), throwable)
  } else {
    xposed.e(config.TAG, toText(message))
  }
}

module.exports = {
  debug: debug,
  info: info,
  warn: warn,
  error: error,
  toText: toText
}

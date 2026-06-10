function safeCall(label, fn, fallback, logger) {
  try {
    return fn()
  } catch (e) {
    if (logger) {
      logger.debug(label + " failed: " + e)
    }
    return fallback
  }
}

function lowerText(value) {
  if (value === null || value === undefined) return ""
  return String(value).toLowerCase()
}

function shouldRedactHeader(config, name) {
  var lower = lowerText(name)
  var i

  for (i = 0; i < config.redactHeaders.length; i++) {
    if (lower === lowerText(config.redactHeaders[i])) {
      return true
    }
  }

  return false
}

function redactHeaderValue(config, name, value) {
  if (shouldRedactHeader(config, name)) {
    return "<redacted>"
  }

  var text = ""

  if (value === null || value === undefined) {
    text = ""
  } else {
    text = String(value)
  }

  if (text.length > config.maxHeaderValueLength) {
    text = text.substring(0, config.maxHeaderValueLength) + "...<truncated>"
  }

  return text
}

function asNumber(value, fallback) {
  var n = Number(value)

  if (isNaN(n)) {
    return fallback
  }

  return n
}

module.exports = {
  safeCall: safeCall,
  redactHeaderValue: redactHeaderValue,
  asNumber: asNumber
}

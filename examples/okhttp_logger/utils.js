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

function hasHeader(config, name) {
  const lower = String(name).toLowerCase()
  for (let i = 0; i < config.redactHeaders.length; i++) {
    if (lower === String(config.redactHeaders[i]).toLowerCase()) {
      return true
    }
  }
  return false
}

function redactHeaderValue(config, name, value) {
  if (hasHeader(config, name)) {
    return "<redacted>"
  }

  let text = String(value)
  if (text.length > config.maxHeaderValueLength) {
    text = text.substring(0, config.maxHeaderValueLength) + "...<truncated>"
  }

  return text
}

function contentLengthOrUnknown(body) {
  if (!body) return -1
  try {
    return body.contentLength()
  } catch (e) {
    return -1
  }
}

module.exports = {
  safeCall,
  redactHeaderValue,
  contentLengthOrUnknown
}

/*
 * XiaoHeiHook documentation search fallback.
 *
 * Some Sphinx 9.x builds for zh_CN/zh_TW generate language_data.js that refers
 * to ChineseStemmer before such a global is defined.  The fallback below is a
 * deliberately small no-op stemmer: it keeps Chinese terms unchanged and only
 * lowercases Latin input, which is enough to keep the built-in search page from
 * crashing while preserving normal token matching.
 */
(function (window) {
  'use strict';

  if (typeof window.ChineseStemmer !== 'undefined') {
    return;
  }

  window.ChineseStemmer = function ChineseStemmer() {};

  window.ChineseStemmer.prototype.stemWord = function stemWord(word) {
    if (word === null || typeof word === 'undefined') {
      return '';
    }
    return String(word).toLowerCase();
  };
})(window);

# Configuration file for the Sphinx documentation builder.

project = 'XiaoHeiHook'
copyright = '2026, XiaoHeiHook'
author = 'XiaoHeiHook'
release = 'v16'

extensions = ['sphinx.ext.intersphinx']
templates_path = ['_templates']
exclude_patterns = []
language = 'zh_CN'

html_theme = 'sphinx_rtd_theme'
html_static_path = ['_static']

# Sphinx 9.x currently emits ``var Stemmer = ChineseStemmer`` for Chinese
# search data without always loading a matching ``ChineseStemmer`` runtime first.
# Keep a tiny fallback loaded before ``language_data.js`` so the search page does
# not fail with ``ChineseStemmer is not defined`` when the documentation is built
# by a newer toolchain.
html_js_files = [
    'chinese_search_stemmer_fallback.js',
]


suppress_warnings = [
    'misc.highlighting_failure'
]

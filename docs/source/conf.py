# Configuration file for the Sphinx documentation builder.

project = 'XiaoHeiHook'
copyright = '2026, XiaoHeiHook'
author = 'XiaoHeiHook'
release = 'v1.30 (107)'

extensions = ['sphinx.ext.intersphinx']
templates_path = ['_templates']
exclude_patterns = []
language = 'zh_CN'

html_theme = 'sphinx_rtd_theme'
html_static_path = ['_static']

suppress_warnings = [
    'misc.highlighting_failure'
]

html_js_files = [
    'chinese_search_stemmer_fallback.js',
]

suppress_warnings = [
    'misc.highlighting_failure'
]

package static

import "embed"

//go:embed index.html apple-ui.css favicon.ico logo.png js/* lib/*
var FS embed.FS

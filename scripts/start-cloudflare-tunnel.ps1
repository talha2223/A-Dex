param(
  [string]$Hostname = "adex-local.example.com"
)

# Starts Cloudflare tunnel (requires cloudflared installed and authenticated).
cloudflared tunnel --url http://127.0.0.1:8080 --hostname $Hostname

param(
  [string]$BackendUrl = "http://127.0.0.1:8080"
)

# Simple health check for local backend API status.
try {
  $response = Invoke-RestMethod -Method Get -Uri "$BackendUrl/api/v1/health"
  Write-Host "Backend health:" ($response | ConvertTo-Json -Depth 3)
} catch {
  Write-Error "Backend health check failed: $($_.Exception.Message)"
  exit 1
}

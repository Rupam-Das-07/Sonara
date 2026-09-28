<#
.SYNOPSIS
    verify-production-smoke.ps1 — Sonara Production Gateway Smoke & Deployment Verification Gate (PowerShell wrapper)

.DESCRIPTION
    Runs node backend/scripts/verify-production-smoke.js with optional target, timeout, and json formatting.
    Produces machine-readable exit codes: 0 = PASS, 1 = FAIL.

.PARAMETER Target
    The production gateway base URL (default: https://sonara.antideploy.com)

.PARAMETER TimeoutMs
    Per-request timeout in milliseconds (default: 10000)

.PARAMETER Json
    Outputs pure JSON for automation/CI pipelines

.PARAMETER VerboseOutput
    Prints detailed subsystem diagnostics
#>

param(
    [string]$Target = "https://sonara.antideploy.com",
    [int]$TimeoutMs = 10000,
    [switch]$Json,
    [switch]$VerboseOutput
)

$argsList = @("backend/scripts/verify-production-smoke.js", "--target=$Target", "--timeout=$TimeoutMs")

if ($Json) {
    $argsList += "--json"
}

if ($VerboseOutput) {
    $argsList += "--verbose"
}

& node $argsList
exit $LASTEXITCODE

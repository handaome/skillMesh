<#
.SYNOPSIS
    把本机的 SSH 公钥注册到 GitHub 账号。

.DESCRIPTION
    用途：本机到 github.com 的 443 不通，但 api.github.com 通，且 22（SSH）通。
    注册了 SSH 公钥后，git clone 走 SSH 就能用，不依赖代理。

    前提：一个带 admin:public_key 权限的 Personal Access Token
    （fine-grained token 需要 "Administration: SSH public keys" 写权限）。
    令牌只从环境变量读，不落盘、不进历史。

.EXAMPLE
    $env:GITHUB_TOKEN = "ghp_xxx"
    .\infra\scripts\register-github-ssh-key.ps1
#>
[CmdletBinding()]
param(
    [string]$KeyFile = "$env:USERPROFILE\.ssh\id_ed25519.pub",
    [string]$Title = "skillmesh-dev"
)

$ErrorActionPreference = "Stop"

if (-not $env:GITHUB_TOKEN) {
    Write-Error "缺少 GITHUB_TOKEN 环境变量。`n  `$env:GITHUB_TOKEN = '<你的 PAT>'"
}

if (-not (Test-Path $KeyFile)) {
    Write-Error "公钥文件不存在: $KeyFile`n  先执行: ssh-keygen -t ed25519 -C skillmesh-dev -f `"$env:USERPROFILE\.ssh\id_ed25519`" -N `"`""
}

$publicKey = (Get-Content -Raw $KeyFile).Trim()
$headers = @{
    "Authorization"        = "Bearer $env:GITHUB_TOKEN"
    "Accept"               = "application/vnd.github+json"
    "X-GitHub-Api-Version" = "2022-11-28"
}

$body = @{ title = $Title; key = $publicKey } | ConvertTo-Json -Compress

Write-Host "注册公钥到 GitHub 账号..."
try {
    $null = Invoke-RestMethod -Method Post -Uri "https://api.github.com/user/keys" `
        -Headers $headers -Body $body -ContentType "application/json"
    Write-Host "已注册。" -ForegroundColor Green
}
catch {
    $status = $_.Exception.Response.StatusCode.value__
    if ($status -eq 422) {
        Write-Host "该公钥已注册过（或标题重复），无需重复添加。" -ForegroundColor Yellow
    }
    else {
        Write-Error "注册失败（HTTP $status）：`n$($_.ErrorDetails.Message)"
    }
}

Write-Host ""
Write-Host "账号当前的 SSH 公钥："
(Invoke-RestMethod -Method Get -Uri "https://api.github.com/user/keys" -Headers $headers) |
    ForEach-Object { "  {0,-24} {1}...{2}" -f $_.title, $_.key.Substring(0, 20), $_.key.Substring($_.key.Length - 8) }

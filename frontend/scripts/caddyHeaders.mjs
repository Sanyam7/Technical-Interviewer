// Prints the Caddy snippet of the security headers (scripts/securityHeaders.mjs) for the API address in VITE_API_BASE_URL.
// Used by deploy/Dockerfile:  node scripts/caddyHeaders.mjs > spa-headers.caddy
import { caddySnippet } from './securityHeaders.mjs';

process.stdout.write(caddySnippet(process.env.VITE_API_BASE_URL));

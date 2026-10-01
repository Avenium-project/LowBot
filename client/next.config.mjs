/** @type {import('next').NextConfig} */
// NEXT_OUTPUT=export builds a static bundle (no SSR) that the Tauri (Windows)
// and Capacitor (Android) shells embed. The default build keeps upstream SSR.
const staticExport = process.env.NEXT_OUTPUT === 'export';

const nextConfig = {
  reactStrictMode: true,
  ...(staticExport ? { output: 'export', trailingSlash: true, images: { unoptimized: true } } : {}),
};

export default nextConfig;

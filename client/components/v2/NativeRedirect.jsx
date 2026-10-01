'use client';
// Inside the Android/Windows shells the bundled UI starts at "/", which is
// the upstream landing page. Jump straight to the workspace instead.
import { useEffect } from 'react';
import { isNative } from '../../lib/v2/api';

export default function NativeRedirect() {
  useEffect(() => { if (isNative()) window.location.replace('/bots/'); }, []);
  return null;
}

import { useId } from 'react'

/**
 * SK-01 — «Сберкот DLMM», the in-app assistant mascot.
 *
 * Hand-authored SVG (not a raster image): on-brand, crisp at 32px FAB → 200px
 * onboarding, themeable, ~3 KB. SberDesign aesthetic — friendly rounded
 * geometry, Sber-green gradient body, white belly, a brand checkmark badge on
 * the chest (the Sber mark). The brand greens are intentionally fixed (this is a
 * brand asset, like a logo) so Сберкот looks identical in light + dark; the
 * surrounding bubble/widget adapts to the theme.
 *
 * `pose` swaps the raised paw + expression:
 *   - greet     waving paw, open smile  (first-run / hello)
 *   - point     paw pointing up-left     (drawing attention to a hint target)
 *   - idle      both paws down, soft smile (default resting)
 *   - celebrate both paws up, big smile  (success — claim done, order filled)
 */
export type SberkotPose = 'greet' | 'point' | 'idle' | 'celebrate'

interface SberkotMascotProps {
  pose?: SberkotPose
  size?: number
  /** pause the subtle idle wobble (also auto-paused under prefers-reduced-motion via CSS) */
  animated?: boolean
  className?: string
  title?: string
}

export default function SberkotMascot({
  pose = 'idle',
  size = 96,
  animated = false,
  className,
  title = 'Сберкот',
}: SberkotMascotProps) {
  // useId → collision-safe gradient ids when several Сберкоты mount at once.
  const uid = useId().replace(/:/g, '')
  const gBody = `sk-body-${uid}`
  const gEar = `sk-ear-${uid}`
  const gBelly = `sk-belly-${uid}`

  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 128 128"
      fill="none"
      xmlns="http://www.w3.org/2000/svg"
      role="img"
      aria-label={title}
      className={`${className ?? ''} ${animated ? 'sberkot--animated' : ''}`.trim()}
    >
      <title>{title}</title>
      <defs>
        <linearGradient id={gBody} x1="20" y1="18" x2="108" y2="118" gradientUnits="userSpaceOnUse">
          <stop offset="0" stopColor="#2FB84B" />
          <stop offset="0.5" stopColor="#21A038" />
          <stop offset="1" stopColor="#0E8F6E" />
        </linearGradient>
        <linearGradient id={gEar} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stopColor="#1C8A30" />
          <stop offset="1" stopColor="#0E6B1E" />
        </linearGradient>
        <radialGradient id={gBelly} cx="0.5" cy="0.42" r="0.62">
          <stop offset="0" stopColor="#FFFFFF" />
          <stop offset="1" stopColor="#E6F7EA" />
        </radialGradient>
      </defs>

      {/* soft contact shadow */}
      <ellipse cx="64" cy="118" rx="34" ry="6" fill="#0E6B1E" opacity="0.16" />

      {/* tail — curls up on the right */}
      <path
        d="M96 96 C118 92 120 64 104 58 C112 70 100 82 88 84 Z"
        fill={`url(#${gBody})`}
      />

      {/* body */}
      <path
        d="M40 64 C40 50 52 44 64 44 C76 44 88 50 88 64 L90 96 C90 108 80 112 64 112 C48 112 38 108 38 96 Z"
        fill={`url(#${gBody})`}
      />
      {/* belly */}
      <ellipse cx="64" cy="86" rx="18" ry="22" fill={`url(#${gBelly})`} />

      {/* ears */}
      <path d="M34 40 L30 16 L52 30 Z" fill={`url(#${gEar})`} />
      <path d="M94 40 L98 16 L76 30 Z" fill={`url(#${gEar})`} />
      <path d="M37 36 L35 24 L46 31 Z" fill="#FFC9D6" opacity="0.85" />
      <path d="M91 36 L93 24 L82 31 Z" fill="#FFC9D6" opacity="0.85" />

      {/* head */}
      <circle cx="64" cy="46" r="30" fill={`url(#${gBody})`} />

      {/* cheeks */}
      <circle cx="46" cy="54" r="6.5" fill="#FF9DB4" opacity="0.55" />
      <circle cx="82" cy="54" r="6.5" fill="#FF9DB4" opacity="0.55" />

      {/* eyes */}
      {pose === 'celebrate' ? (
        // happy closed ^_^ eyes
        <>
          <path d="M48 44 q5 -7 10 0" stroke="#0B3D17" strokeWidth="3.4" strokeLinecap="round" fill="none" />
          <path d="M70 44 q5 -7 10 0" stroke="#0B3D17" strokeWidth="3.4" strokeLinecap="round" fill="none" />
        </>
      ) : (
        <>
          <ellipse cx="53" cy="45" rx="6" ry="7.5" fill="#FFFFFF" />
          <ellipse cx="75" cy="45" rx="6" ry="7.5" fill="#FFFFFF" />
          <circle cx="54" cy="46" r="3.4" fill="#0B3D17" />
          <circle cx="76" cy="46" r="3.4" fill="#0B3D17" />
          <circle cx="55.3" cy="44.6" r="1.1" fill="#FFFFFF" />
          <circle cx="77.3" cy="44.6" r="1.1" fill="#FFFFFF" />
        </>
      )}

      {/* nose */}
      <path d="M61.5 53 L66.5 53 L64 56.5 Z" fill="#FF7A98" />
      {/* mouth */}
      {pose === 'idle' ? (
        <path d="M58 58 q6 5 12 0" stroke="#0B3D17" strokeWidth="2.4" strokeLinecap="round" fill="none" />
      ) : (
        <path d="M56 57 q8 9 16 0" stroke="#0B3D17" strokeWidth="2.6" strokeLinecap="round" fill="none" />
      )}

      {/* whiskers */}
      <g stroke="#0B3D17" strokeWidth="1.6" strokeLinecap="round" opacity="0.55">
        <path d="M40 50 L28 47" /><path d="M40 54 L28 55" />
        <path d="M88 50 L100 47" /><path d="M88 54 L100 55" />
      </g>

      {/* brand checkmark badge on chest */}
      <circle cx="64" cy="92" r="9.5" fill="#FFFFFF" />
      <path d="M59.5 92 L62.7 95.4 L69 88.5" stroke="#21A038" strokeWidth="2.6" strokeLinecap="round" strokeLinejoin="round" fill="none" />

      {/* left paw (down) */}
      <ellipse cx="50" cy="108" rx="8" ry="6.5" fill={`url(#${gBody})`} />

      {/* right arm/paw — pose-dependent */}
      {pose === 'idle' && (
        <ellipse cx="78" cy="108" rx="8" ry="6.5" fill={`url(#${gBody})`} />
      )}
      {pose === 'greet' && (
        <g className="sberkot__wave">
          <path d="M84 72 C96 64 104 66 104 56 C104 52 100 50 96 52 L86 60 Z" fill={`url(#${gBody})`} />
          <ellipse cx="100" cy="52" rx="7" ry="6" fill={`url(#${gBody})`} />
        </g>
      )}
      {pose === 'point' && (
        <g>
          <path d="M44 70 C30 60 24 64 22 54 C21 50 25 47 29 50 L42 60 Z" fill={`url(#${gBody})`} />
          <ellipse cx="24" cy="52" rx="6.5" ry="6" fill={`url(#${gBody})`} />
        </g>
      )}
      {pose === 'celebrate' && (
        <>
          <path d="M84 72 C96 60 100 54 108 50 C112 48 114 52 112 56 L92 76 Z" fill={`url(#${gBody})`} />
          <ellipse cx="110" cy="52" rx="6.5" ry="6" fill={`url(#${gBody})`} />
          <path d="M44 72 C32 60 28 54 20 50 C16 48 14 52 16 56 L36 76 Z" fill={`url(#${gBody})`} />
          <ellipse cx="18" cy="52" rx="6.5" ry="6" fill={`url(#${gBody})`} />
        </>
      )}
    </svg>
  )
}

import { useId } from 'react'

/**
 * SK-01 — «Сберкот», the in-app assistant mascot (remodelled 2026-05-29 after
 * the real Sber mascot).
 *
 * The real СберКот is a chubby, anthropomorphic GREY (blue-grey) cat with big
 * green eyes, a white muzzle and pink nose, who wears a green Sber hoodie — the
 * green is the clothing, not the fur. This hand-authored inline SVG (no raster,
 * no deps) captures that: grey fur head + paws, white muzzle, big green eyes
 * with catch-lights, pink nose/inner-ears, and a Sber-green hooded sweater with
 * a white checkmark badge on the chest. The palette hex is the source of truth
 * (allowlisted in scripts/check-no-hex-in-tsx.mjs) so he reads identically in
 * light AND dark themes; only the surrounding bubble adapts.
 *
 * `pose` swaps the raised paw + expression:
 *   - greet     waving paw, open smile    (first-run / hello)
 *   - point     paw pointing up-left       (drawing attention to a hint target)
 *   - idle      both paws down, soft smile (default resting)
 *   - celebrate both paws up, happy ^_^    (success — claim done, order filled)
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
  const gFur = `sk-fur-${uid}`
  const gFurDk = `sk-furdk-${uid}`
  const gHood = `sk-hood-${uid}`
  const gHoodDk = `sk-hooddk-${uid}`
  const gMuzzle = `sk-muz-${uid}`
  const gIris = `sk-iris-${uid}`
  const gShine = `sk-shine-${uid}`
  const gBadge = `sk-badge-${uid}`

  const eyesUp = pose === 'celebrate'

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
        {/* blue-grey fur */}
        <linearGradient id={gFur} x1="36" y1="16" x2="94" y2="86" gradientUnits="userSpaceOnUse">
          <stop offset="0" stopColor="#D2DBE5" />
          <stop offset="0.5" stopColor="#AAB6C3" />
          <stop offset="1" stopColor="#8997A7" />
        </linearGradient>
        <linearGradient id={gFurDk} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stopColor="#A0ADBB" />
          <stop offset="1" stopColor="#727F8D" />
        </linearGradient>
        {/* Sber-green hoodie */}
        <linearGradient id={gHood} x1="40" y1="64" x2="92" y2="120" gradientUnits="userSpaceOnUse">
          <stop offset="0" stopColor="#34C152" />
          <stop offset="0.5" stopColor="#21A038" />
          <stop offset="1" stopColor="#138438" />
        </linearGradient>
        <linearGradient id={gHoodDk} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stopColor="#1C8A30" />
          <stop offset="1" stopColor="#0E6B1E" />
        </linearGradient>
        <radialGradient id={gMuzzle} cx="0.5" cy="0.42" r="0.7">
          <stop offset="0" stopColor="#FFFFFF" />
          <stop offset="1" stopColor="#EDF2F6" />
        </radialGradient>
        {/* green eyes */}
        <radialGradient id={gIris} cx="0.4" cy="0.34" r="0.85">
          <stop offset="0" stopColor="#3DB565" />
          <stop offset="0.55" stopColor="#1A7B40" />
          <stop offset="1" stopColor="#0A4A26" />
        </radialGradient>
        <linearGradient id={gShine} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stopColor="#FFFFFF" stopOpacity="0.5" />
          <stop offset="1" stopColor="#FFFFFF" stopOpacity="0" />
        </linearGradient>
        <radialGradient id={gBadge} cx="0.5" cy="0.38" r="0.7">
          <stop offset="0" stopColor="#FFFFFF" />
          <stop offset="1" stopColor="#EAF7EC" />
        </radialGradient>
      </defs>

      {/* soft contact shadow */}
      <ellipse cx="64" cy="121" rx="30" ry="5" fill="#5A6675" opacity="0.16" />

      {/* tail — grey curl behind the body on the right */}
      <path d="M95 100 C117 97 119 71 105 61 C100 57 95 59 96 66 C97 73 104 75 103 84 C102 92 95 95 88 91 Z" fill={`url(#${gFurDk})`} />

      {/* green hooded sweater (torso) */}
      <path d="M43 82 C43 67 53 62 64 62 C75 62 85 67 85 82 L87 105 C87 114 77 118 64 118 C51 118 41 114 41 105 Z" fill={`url(#${gHood})`} />
      {/* collar V at the neck */}
      <path d="M47 66 Q64 77 81 66 L78 72 Q64 81 50 72 Z" fill={`url(#${gHoodDk})`} />
      {/* hood resting behind the shoulders */}
      <path d="M40 72 C34 64 33 56 36 50 C40 60 52 64 64 64 C76 64 88 60 92 50 C95 56 94 64 88 72 Z" fill={`url(#${gHoodDk})`} opacity="0.85" />

      {/* chest checkmark badge */}
      <circle cx="64" cy="94" r="8.5" fill={`url(#${gBadge})`} />
      <path d="M59.8 94 L62.7 97 L68.4 90.4" stroke="#21A038" strokeWidth="2.6" strokeLinecap="round" strokeLinejoin="round" fill="none" />

      {/* head */}
      <circle cx="64" cy="45" r="33" fill={`url(#${gFur})`} />
      {/* darker grey crown (tabby hint) */}
      <path d="M34 40 C40 22 52 14 64 14 C76 14 88 22 94 40 C84 32 74 29 64 29 C54 29 44 32 34 40 Z" fill={`url(#${gFurDk})`} opacity="0.5" />
      {/* glossy crown sheen */}
      <ellipse cx="55" cy="27" rx="18" ry="9" fill={`url(#${gShine})`} opacity="0.85" />

      {/* ears */}
      <path d="M40 28 L33 6 L56 22 Z" fill={`url(#${gFurDk})`} />
      <path d="M88 28 L95 6 L72 22 Z" fill={`url(#${gFurDk})`} />
      <path d="M41 25 L37 13 L51 22 Z" fill="#FFC2D2" opacity="0.9" />
      <path d="M87 25 L91 13 L77 22 Z" fill="#FFC2D2" opacity="0.9" />

      {/* white muzzle */}
      <ellipse cx="64" cy="54" rx="19" ry="13" fill={`url(#${gMuzzle})`} />

      {/* blush cheeks */}
      <ellipse cx="42" cy="52" rx="6" ry="4.5" fill="#FFA7BE" opacity="0.5" />
      <ellipse cx="86" cy="52" rx="6" ry="4.5" fill="#FFA7BE" opacity="0.5" />

      {/* eyes */}
      {eyesUp ? (
        <g stroke="#0C3D18" strokeWidth="3.6" strokeLinecap="round" fill="none">
          <path d="M45 44 q6 -8 12 0" />
          <path d="M71 44 q6 -8 12 0" />
        </g>
      ) : (
        <>
          <ellipse cx="51" cy="43" rx="8" ry="10" fill="#FFFFFF" />
          <ellipse cx="77" cy="43" rx="8" ry="10" fill="#FFFFFF" />
          <ellipse cx="52" cy="44" rx="6" ry="7.8" fill={`url(#${gIris})`} />
          <ellipse cx="78" cy="44" rx="6" ry="7.8" fill={`url(#${gIris})`} />
          <circle cx="52" cy="45.5" r="3" fill="#08311A" />
          <circle cx="78" cy="45.5" r="3" fill="#08311A" />
          {/* catch-lights */}
          <circle cx="54.2" cy="40.6" r="2.4" fill="#FFFFFF" />
          <circle cx="80.2" cy="40.6" r="2.4" fill="#FFFFFF" />
          <circle cx="49.6" cy="46.6" r="1.2" fill="#FFFFFF" opacity="0.9" />
          <circle cx="75.6" cy="46.6" r="1.2" fill="#FFFFFF" opacity="0.9" />
        </>
      )}

      {/* nose */}
      <path d="M60.5 56 Q64 53.6 67.5 56 Q64 60 60.5 56 Z" fill="#FF7F9C" />

      {/* mouth */}
      {pose === 'idle' ? (
        <path d="M58 61 Q64 66 70 61" stroke="#0C3D18" strokeWidth="2.4" strokeLinecap="round" fill="none" />
      ) : eyesUp ? (
        <path d="M55 60 Q64 70 73 60" stroke="#0C3D18" strokeWidth="2.8" strokeLinecap="round" fill="none" />
      ) : (
        <path d="M56 60 Q64 69 72 60" stroke="#0C3D18" strokeWidth="2.6" strokeLinecap="round" fill="none" />
      )}

      {/* whiskers */}
      <g stroke="#8997A7" strokeWidth="1.4" strokeLinecap="round" opacity="0.7">
        <path d="M38 53 Q29 51 24 52" />
        <path d="M38 58 Q29 59 25 61" />
        <path d="M90 53 Q99 51 104 52" />
        <path d="M90 58 Q99 59 103 61" />
      </g>

      {/* left arm/paw — green sleeve + grey paw (down for idle/greet/point) */}
      {pose !== 'celebrate' && (
        <>
          <ellipse cx="46" cy="104" rx="9" ry="6" fill={`url(#${gHoodDk})`} />
          <ellipse cx="45" cy="112" rx="8" ry="6.5" fill={`url(#${gFur})`} />
        </>
      )}

      {/* right arm/paw — pose-dependent */}
      {pose === 'idle' && (
        <>
          <ellipse cx="82" cy="104" rx="9" ry="6" fill={`url(#${gHoodDk})`} />
          <ellipse cx="83" cy="112" rx="8" ry="6.5" fill={`url(#${gFur})`} />
        </>
      )}

      {pose === 'greet' && (
        // waving paw — pivots near (90,60) to match the CSS wave keyframes
        <g className="sberkot__wave">
          <path d="M82 80 C95 74 101 68 101 60 C101 56 97 54 93 57 L82 66 Z" fill={`url(#${gHood})`} />
          <circle cx="100" cy="57" r="7.5" fill={`url(#${gFur})`} />
          <circle cx="97.5" cy="53.5" r="1.4" fill="#FFC2D2" opacity="0.85" />
          <circle cx="102.5" cy="54" r="1.4" fill="#FFC2D2" opacity="0.85" />
        </g>
      )}

      {pose === 'point' && (
        <g>
          <path d="M46 80 C32 70 24 64 22 56 C21 52 25 49 29 52 L44 64 Z" fill={`url(#${gHood})`} />
          <circle cx="23" cy="54" r="7" fill={`url(#${gFur})`} />
          <circle cx="20.5" cy="50.5" r="1.3" fill="#FFC2D2" opacity="0.85" />
          <circle cx="25.5" cy="51" r="1.3" fill="#FFC2D2" opacity="0.85" />
        </g>
      )}

      {pose === 'celebrate' && (
        <>
          <path d="M82 80 C95 68 100 60 108 54 C112 51 116 55 113 59 L90 84 Z" fill={`url(#${gHood})`} />
          <circle cx="112" cy="55" r="7" fill={`url(#${gFur})`} />
          <path d="M46 80 C33 68 28 60 20 54 C16 51 12 55 15 59 L38 84 Z" fill={`url(#${gHood})`} />
          <circle cx="16" cy="55" r="7" fill={`url(#${gFur})`} />
        </>
      )}
    </svg>
  )
}

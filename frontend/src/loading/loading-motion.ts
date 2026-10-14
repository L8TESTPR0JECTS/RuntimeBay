import { gsap } from 'gsap';

/** Shared by the Angular overlay and native startup. Rotation is deliberately absent. */
export function breatheLogo(logo: HTMLElement, halo: HTMLElement): () => void {
  const media = gsap.matchMedia();
  media.add({ reduce: '(prefers-reduced-motion: reduce)', animate: '(prefers-reduced-motion: no-preference)' }, context => {
    if (context.conditions?.['reduce']) {
      gsap.set(logo, { scale: 1, opacity: 1, '--breath-color': '0%' });
      gsap.set(halo, { scale: 1, opacity: .12 });
      return;
    }
    gsap.fromTo(logo, { scale: .94, opacity: .72, '--breath-color': '0%' }, {
      scale: 1.06, opacity: 1, '--breath-color': '100%', duration: 1.65,
      repeat: -1, yoyo: true, ease: 'sine.inOut'
    });
    gsap.fromTo(halo, { scale: .92, opacity: .08 }, {
      scale: 1.12, opacity: .2, duration: 1.65, repeat: -1, yoyo: true, ease: 'sine.inOut'
    });
  });
  return () => media.revert();
}

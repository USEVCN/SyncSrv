import { useEffect, useState } from "react";
import { bytes } from "../../shared/format";

const MIB = 1024 ** 2;
const point = (fraction: number, radius = 88) => {
  const angle = (150 + fraction * 240) * Math.PI / 180;
  return { x: 120 + radius * Math.cos(angle), y: 106 + radius * Math.sin(angle) };
};
const arc = "M 43.79 150 A 88 88 0 1 1 196.21 150";
const speedScale = (speed: number) => {
  const mib = Math.max(1, speed / MIB);
  const base = 10 ** Math.floor(Math.log10(mib));
  return ([1, 2, 5, 10].find(step => step * base >= mib) || 10) * base * MIB;
};

export function TransferSpeedGauge({ value, label, state, verification = false }: {
  value: number | null; label: string; state: string; verification?: boolean;
}) {
  const speed = value !== null && Number.isFinite(value) && value >= 0 ? value : null;
  const requiredScale = speedScale(speed || 0);
  const [observedScale, setObservedScale] = useState(requiredScale);
  // Keep the scale steady when throughput falls; the parent resets it for a new run or metric.
  const scale = Math.max(observedScale, requiredScale);
  useEffect(() => { setObservedScale(scale); }, [scale]);
  const fraction = speed === null ? 0 : Math.min(1, speed / scale);
  const marker = point(fraction);
  const formatted = speed === null ? "—" : bytes(Math.round(speed));
  const [amount, unit] = speed === null ? ["—", ""] : formatted.split(" ");
  return <div className={`transfer-speed-gauge${verification ? " is-verifying" : ""}${speed === null ? " is-unavailable" : ""}`}>
    <div className="transfer-speed-heading"><span>{label}速度</span><span className="transfer-speed-state">{state}</span></div>
    <div className="transfer-speed-instrument" role="img" aria-label={speed === null ? `${label}速度 —` : `${label} ${formatted}/s`}>
      <svg viewBox="0 0 240 174" aria-hidden="true">
        <path className="speed-track" d={arc} />
        {Array.from({ length: 25 }, (_, i) => {
          const a = point(i / 24, 76), b = point(i / 24, i % 4 === 0 ? 68 : 72);
          return <line key={i} className="speed-tick" x1={a.x} y1={a.y} x2={b.x} y2={b.y} />;
        })}
        <path className="speed-fill" d={arc} pathLength={100} strokeDasharray={`${fraction * 100} 100`} opacity={fraction > 0 ? 1 : 0} />
        {speed !== null && speed > 0 && <circle className="speed-marker" cx={marker.x} cy={marker.y} r="4" />}
        <text className="speed-scale-label" x="30" y="172" textAnchor="middle">0</text>
        <text className="speed-scale-label" x="205" y="172" textAnchor="middle">{bytes(scale).replace(".0 ", " ")}/s</text>
      </svg>
      <div className="transfer-speed-value"><strong>{amount}</strong><span>{unit ? `${unit}/s` : "暂无实时速度"}</span></div>
    </div>
    <div className="transfer-speed-caption">自动量程 · {bytes(scale).replace(".0 ", " ")}/s</div>
  </div>;
}

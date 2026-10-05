import { useState } from "react";
import { Check, Copy } from "lucide-react";

function prettyXml(raw: string): string {
  const compact = raw.replace(/>\s+</g, "><").trim();
  let indent = 0;
  return compact.replace(/<[^>]+>/g, (tag) => {
    const closing = /^<\//.test(tag);
    const selfClosing = /\/>$/.test(tag) || /^<\?/.test(tag);
    if (closing) {
      indent = Math.max(indent - 1, 0);
    }
    const line = `${"  ".repeat(indent)}${tag}`;
    if (!closing && !selfClosing) {
      indent += 1;
    }
    return `\n${line}`;
  }).trim();
}

export function XmlViewer({ xml, label }: { xml: string; label: string }) {
  const [copied, setCopied] = useState(false);
  const formatted = prettyXml(xml);

  async function copy() {
    await navigator.clipboard.writeText(formatted);
    setCopied(true);
    window.setTimeout(() => setCopied(false), 1500);
  }

  return (
    <div className="relative">
      <button
        type="button"
        onClick={copy}
        className="absolute right-2 top-2 inline-flex items-center gap-1 rounded bg-white/90 px-2 py-1 text-xs text-slate-600 shadow-sm hover:bg-white"
      >
        {copied ? <Check size={12} /> : <Copy size={12} />}
        {copied ? "Copied" : `Copy ${label}`}
      </button>
      <pre className="max-h-[28rem] overflow-auto rounded-lg bg-slate-950 p-4 text-xs leading-5 text-emerald-100">
        {formatted}
      </pre>
    </div>
  );
}

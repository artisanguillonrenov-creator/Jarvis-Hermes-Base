/**
 * A from-scratch chat surface for builds where the embedded `hermes --tui`
 * cannot run at all (no Node.js available — the Android/Chaquopy build).
 *
 * web/AGENTS.md says not to reimplement the chat experience in React and to
 * extend Ink instead — that rule assumes Node is always available to run
 * Ink. On this platform it isn't, so extending Ink is not an option; this
 * page is the deliberate, documented exception for that one platform,
 * talking to the exact same tui_gateway JSON-RPC backend (`/api/ws`) that
 * both the embedded TUI and the Electron desktop app use — same sessions,
 * same tools, same models, same image generation. Only the presentation
 * layer is new.
 *
 * Protocol (see tui_gateway/AGENTS.md's method/event table):
 *   session.create                          -> { session_id }
 *   prompt.submit {session_id, text}        -> queued; reply comes as events
 *   message.start / message.delta / message.complete   (payload.text)
 *   tool.start / tool.complete               (payload.name)
 *   image.attach_bytes {session_id, content_base64, filename} -> queues an
 *     image for the NEXT prompt.submit (server-side one-shot queue)
 *   file.attach {session_id, data_url, name} -> { ref_text: "@file:..." },
 *     which must be appended to the prompt text
 */
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useSearchParams } from "react-router";
import {
  Copy,
  FileText,
  FolderUp,
  History,
  ImagePlus,
  RotateCcw,
  Send,
  X,
} from "lucide-react";

import { copyTextToClipboard } from "@/lib/clipboard";
import { GatewayClient, type ConnectionState } from "@/lib/gatewayClient";

interface PendingAttachment {
  id: string;
  kind: "image" | "file";
  name: string;
  /** Preview only for images (object URL); never sent to the server. */
  previewUrl?: string;
}

interface ChatMessage {
  id: string;
  role: "user" | "assistant";
  text: string;
  /** Image URLs/data-URIs found in the assistant's final text, rendered inline. */
  images: string[];
  streaming: boolean;
  /** Source user text this assistant reply answers — regenerate resubmits it. */
  sourceUserText?: string;
}

/** One row of `session.list` — a past conversation the user can click back into. */
interface SessionSummary {
  id: string;
  title: string;
  preview: string;
  started_at: number;
  message_count: number;
  source: string;
}

let nextId = 0;
function newId(): string {
  nextId += 1;
  return `m${Date.now()}-${nextId}`;
}

// Assistant text commonly references a generated image as Markdown
// (`![alt](path)`) or a bare path/URL ending in a common image extension —
// catch both without pulling in a full Markdown renderer.
const IMAGE_MD_RE = /!\[[^\]]*\]\(([^)]+)\)/g;
const IMAGE_PATH_RE =
  /(https?:\/\/\S+\.(?:png|jpe?g|gif|webp)|\/\S+\.(?:png|jpe?g|gif|webp))/gi;

function extractImages(text: string): string[] {
  const found = new Set<string>();
  for (const m of text.matchAll(IMAGE_MD_RE)) found.add(m[1]);
  for (const m of text.matchAll(IMAGE_PATH_RE)) found.add(m[1]);
  return Array.from(found);
}

function readFileAsDataUrl(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result));
    reader.onerror = () => reject(reader.error ?? new Error("read failed"));
    reader.readAsDataURL(file);
  });
}

export default function SimpleChatPage({ isActive }: { isActive?: boolean }) {
  const [connState, setConnState] = useState<ConnectionState>("idle");
  const [sessionId, setSessionId] = useState<string | null>(null);
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [draft, setDraft] = useState("");
  const [attachments, setAttachments] = useState<PendingAttachment[]>([]);
  const [sending, setSending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // Clickable "past conversations" panel — the in-page alternative to the
  // separate Sessions page, which wasn't giving the user a working path
  // back to old conversations on this build.
  const [sessionList, setSessionList] = useState<SessionSummary[]>([]);
  const [sessionListOpen, setSessionListOpen] = useState(false);
  const [sessionListLoading, setSessionListLoading] = useState(false);
  const [sessionListError, setSessionListError] = useState<string | null>(null);

  const gw = useMemo(() => new GatewayClient(), []);
  const transcriptRef = useRef<HTMLDivElement>(null);
  const imageInputRef = useRef<HTMLInputElement>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const folderInputRef = useRef<HTMLInputElement>(null);
  // SessionsPage's "resume" action links here as /chat?resume=<id> (same
  // convention the old PTY-backed ChatPage used) — read once on mount.
  const [searchParams, setSearchParams] = useSearchParams();
  const resumeIdRef = useRef(searchParams.get("resume"));

  // Shared by the mount-time resume (via ?resume=) and the in-page session
  // list's click-to-resume — both need to turn a session.resume/create
  // response into transcript state the same way.
  const applyResumeResult = useCallback(
    (res: { session_id: string; messages?: { role: string; text?: string }[] }) => {
      setSessionId(res.session_id);
      setMessages(
        (res.messages ?? [])
          .filter(
            (m): m is { role: "user" | "assistant"; text: string } =>
              (m.role === "user" || m.role === "assistant") && !!m.text,
          )
          .map((m) => ({
            id: newId(),
            role: m.role,
            text: m.text,
            images: m.role === "assistant" ? extractImages(m.text) : [],
            streaming: false,
          })),
      );
    },
    [],
  );

  useEffect(() => {
    let cancelled = false;
    const offState = gw.onState(setConnState);
    const offStart = gw.on("message.start", () => {
      setMessages((prev) => [
        ...prev,
        { id: newId(), role: "assistant", text: "", images: [], streaming: true },
      ]);
    });
    const offDelta = gw.on<{ text?: string }>("message.delta", (ev) => {
      const chunk = ev.payload?.text;
      if (!chunk) return;
      setMessages((prev) => {
        if (prev.length === 0) return prev;
        const next = prev.slice();
        const last = next[next.length - 1];
        if (last.role !== "assistant") return prev;
        next[next.length - 1] = { ...last, text: last.text + chunk };
        return next;
      });
    });
    const offComplete = gw.on<{ text?: string }>("message.complete", (ev) => {
      const finalText = ev.payload?.text ?? "";
      setSending(false);
      setMessages((prev) => {
        if (prev.length === 0) return prev;
        const next = prev.slice();
        const last = next[next.length - 1];
        if (last.role !== "assistant") return prev;
        next[next.length - 1] = {
          ...last,
          text: finalText,
          images: extractImages(finalText),
          streaming: false,
        };
        return next;
      });
    });
    const offError = gw.on<{ message?: string }>("error", (ev) => {
      if (ev.payload?.message) setError(ev.payload.message);
      setSending(false);
    });

    const resumeId = resumeIdRef.current;
    gw.connect()
      .then(() =>
        resumeId
          ? gw.request<{
              session_id: string;
              messages?: { role: string; text?: string }[];
            }>("session.resume", { session_id: resumeId })
          : gw
              .request<{ session_id: string }>("session.create", {})
              .then((res) => ({ ...res, messages: undefined })),
      )
      .then((res) => {
        if (cancelled) return;
        applyResumeResult(res);
      })
      .catch((e) => {
        if (!cancelled) setError(e instanceof Error ? e.message : String(e));
      });

    return () => {
      cancelled = true;
      offState();
      offStart();
      offDelta();
      offComplete();
      offError();
      gw.close();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    transcriptRef.current?.scrollTo({ top: transcriptRef.current.scrollHeight });
  }, [messages]);

  const submit = useCallback(
    async (text: string) => {
      const trimmed = text.trim();
      if (!trimmed || !sessionId || sending) return;
      setError(null);
      setSending(true);
      setMessages((prev) => [
        ...prev,
        { id: newId(), role: "user", text: trimmed, images: [], streaming: false },
      ]);
      try {
        await gw.request("prompt.submit", { session_id: sessionId, text: trimmed });
      } catch (e) {
        setSending(false);
        setError(e instanceof Error ? e.message : String(e));
      }
    },
    [gw, sessionId, sending],
  );

  const handleSend = useCallback(() => {
    if (!draft.trim() && attachments.length === 0) return;
    // @file: refs ride the prompt text itself; images were already queued
    // server-side by image.attach_bytes as each was picked, so only file
    // refs need appending here.
    const fileRefs = attachments
      .filter((a) => a.kind === "file")
      .map((a) => `\n@file:${a.name}`)
      .join("");
    const text = draft.trim() || "(pièce jointe)";
    void submit(text + fileRefs);
    setDraft("");
    attachments.forEach((a) => a.previewUrl && URL.revokeObjectURL(a.previewUrl));
    setAttachments([]);
  }, [draft, attachments, submit]);

  const handleRegenerate = useCallback(
    (sourceUserText: string | undefined) => {
      if (sourceUserText) void submit(sourceUserText);
    },
    [submit],
  );

  const attachImage = useCallback(
    async (file: File) => {
      if (!sessionId) return;
      try {
        const dataUrl = await readFileAsDataUrl(file);
        const base64 = dataUrl.split(",", 2)[1] ?? "";
        await gw.request("image.attach_bytes", {
          session_id: sessionId,
          content_base64: base64,
          filename: file.name,
        });
        setAttachments((prev) => [
          ...prev,
          { id: newId(), kind: "image", name: file.name, previewUrl: dataUrl },
        ]);
      } catch (e) {
        setError(e instanceof Error ? e.message : String(e));
      }
    },
    [gw, sessionId],
  );

  const attachFile = useCallback(
    async (file: File) => {
      if (!sessionId) return;
      try {
        const dataUrl = await readFileAsDataUrl(file);
        const res = await gw.request<{ ref_text?: string; name?: string }>(
          "file.attach",
          { session_id: sessionId, data_url: dataUrl, name: file.name },
        );
        setAttachments((prev) => [
          ...prev,
          { id: newId(), kind: "file", name: res.ref_text ? file.name : file.name },
        ]);
      } catch (e) {
        setError(e instanceof Error ? e.message : String(e));
      }
    },
    [gw, sessionId],
  );

  const removeAttachment = useCallback((id: string) => {
    setAttachments((prev) => {
      const target = prev.find((a) => a.id === id);
      if (target?.previewUrl) URL.revokeObjectURL(target.previewUrl);
      return prev.filter((a) => a.id !== id);
    });
  }, []);

  // A stuck session (e.g. a model's context-overflow/compression cooldown)
  // has no other way to recover short of a fresh session_id — a brand new
  // AIAgent instance, so any per-session error state resets with it.
  const startNewChat = useCallback(() => {
    resumeIdRef.current = null;
    setSearchParams((prev) => {
      const next = new URLSearchParams(prev);
      next.delete("resume");
      return next;
    });
    setMessages([]);
    setAttachments((prev) => {
      prev.forEach((a) => a.previewUrl && URL.revokeObjectURL(a.previewUrl));
      return [];
    });
    setDraft("");
    setError(null);
    setSessionId(null);
    gw.request<{ session_id: string }>("session.create", {})
      .then((res) => setSessionId(res.session_id))
      .catch((e) => setError(e instanceof Error ? e.message : String(e)));
  }, [gw, setSearchParams]);

  const loadSessionList = useCallback(() => {
    setSessionListLoading(true);
    setSessionListError(null);
    gw.request<{ sessions: SessionSummary[] }>("session.list", { limit: 50 })
      .then((res) => setSessionList(res.sessions ?? []))
      .catch((e) => setSessionListError(e instanceof Error ? e.message : String(e)))
      .finally(() => setSessionListLoading(false));
  }, [gw]);

  const toggleSessionList = useCallback(() => {
    setSessionListOpen((prev) => {
      const next = !prev;
      if (next) loadSessionList();
      return next;
    });
  }, [loadSessionList]);

  // Clicking a past conversation resumes it in place — same session.resume
  // call the mount-time ?resume= path uses, just triggered from inside the
  // chat page itself instead of requiring a trip through the Sessions page.
  const resumeSession = useCallback(
    (id: string) => {
      setSessionListOpen(false);
      if (id === sessionId) return;
      setError(null);
      setMessages([]);
      setSessionId(null);
      resumeIdRef.current = id;
      setSearchParams((prev) => {
        const next = new URLSearchParams(prev);
        next.set("resume", id);
        return next;
      });
      gw.request<{
        session_id: string;
        messages?: { role: string; text?: string }[];
      }>("session.resume", { session_id: id })
        .then(applyResumeResult)
        .catch((e) => setError(e instanceof Error ? e.message : String(e)));
    },
    [gw, sessionId, setSearchParams, applyResumeResult],
  );

  if (isActive === false) return null;

  const connected = connState === "open" && !!sessionId;

  return (
    <div className="flex h-full flex-col">
      <div className="relative flex items-center justify-between border-b border-border px-4 py-2">
        <button
          type="button"
          className="flex items-center gap-1.5 rounded-lg border border-border px-3 py-1.5 text-xs hover:bg-muted"
          onClick={toggleSessionList}
        >
          <History size={14} />
          Historique
        </button>
        <button
          type="button"
          className="rounded-lg border border-border px-3 py-1.5 text-xs hover:bg-muted disabled:opacity-40"
          disabled={sending}
          onClick={startNewChat}
        >
          + Nouvelle conversation
        </button>

        {sessionListOpen && (
          <div className="absolute left-4 right-4 top-full z-20 mt-1 max-h-80 overflow-y-auto rounded-lg border border-border bg-background shadow-lg sm:left-auto sm:right-4 sm:w-96">
            <div className="flex items-center justify-between border-b border-border px-3 py-2">
              <span className="text-xs font-medium text-muted-foreground">
                Conversations précédentes
              </span>
              <button
                type="button"
                onClick={() => setSessionListOpen(false)}
                aria-label="Fermer"
              >
                <X size={14} />
              </button>
            </div>
            {sessionListLoading && (
              <div className="px-3 py-4 text-center text-xs text-muted-foreground">
                Chargement…
              </div>
            )}
            {sessionListError && (
              <div className="px-3 py-4 text-center text-xs text-destructive">
                {sessionListError}
              </div>
            )}
            {!sessionListLoading && !sessionListError && sessionList.length === 0 && (
              <div className="px-3 py-4 text-center text-xs text-muted-foreground">
                Aucune conversation trouvée.
              </div>
            )}
            {!sessionListLoading &&
              sessionList.map((s) => (
                <button
                  key={s.id}
                  type="button"
                  className={`flex w-full flex-col items-start gap-0.5 border-b border-border px-3 py-2 text-left last:border-b-0 hover:bg-muted ${
                    s.id === sessionId ? "bg-muted" : ""
                  }`}
                  onClick={() => resumeSession(s.id)}
                >
                  <span className="w-full truncate text-sm font-medium">
                    {s.title || (s.preview ? s.preview.slice(0, 60) : "Sans titre")}
                  </span>
                  <span className="flex w-full items-center gap-1.5 text-xs text-muted-foreground">
                    <span>{s.message_count} messages</span>
                    {s.started_at ? (
                      <span>· {new Date(s.started_at * 1000).toLocaleString("fr-FR")}</span>
                    ) : null}
                  </span>
                </button>
              ))}
          </div>
        )}
      </div>
      <div ref={transcriptRef} className="flex-1 overflow-y-auto px-4 py-4">
        {messages.length === 0 && (
          <div className="mx-auto mt-16 max-w-md text-center text-sm text-muted-foreground">
            {connected
              ? "Écris un message pour commencer."
              : "Connexion à Hermès en cours…"}
          </div>
        )}
        <div className="mx-auto flex max-w-3xl flex-col gap-4">
          {messages.map((m, i) => {
            const priorUser =
              m.role === "assistant"
                ? [...messages.slice(0, i)].reverse().find((x) => x.role === "user")
                    ?.text
                : undefined;
            return (
              <div
                key={m.id}
                className={`flex ${m.role === "user" ? "justify-end" : "justify-start"}`}
              >
                <div
                  className={`max-w-[85%] rounded-2xl px-4 py-2.5 text-sm whitespace-pre-wrap break-words ${
                    m.role === "user"
                      ? "bg-primary text-primary-foreground"
                      : "bg-muted"
                  }`}
                >
                  {m.text || (m.streaming ? "…" : "")}
                  {m.images.length > 0 && (
                    <div className="mt-2 flex flex-wrap gap-2">
                      {m.images.map((src) => (
                        <img
                          key={src}
                          src={src}
                          alt="Image générée"
                          className="max-h-64 max-w-full rounded-lg border border-border"
                        />
                      ))}
                    </div>
                  )}
                  {m.role === "assistant" && !m.streaming && (
                    <div className="mt-2 flex gap-1 opacity-70">
                      <button
                        type="button"
                        title="Copier"
                        className="rounded p-1 hover:bg-black/10"
                        onClick={() => void copyTextToClipboard(m.text)}
                      >
                        <Copy size={14} />
                      </button>
                      <button
                        type="button"
                        title="Régénérer"
                        className="rounded p-1 hover:bg-black/10"
                        onClick={() => handleRegenerate(priorUser)}
                        disabled={!priorUser || sending}
                      >
                        <RotateCcw size={14} />
                      </button>
                    </div>
                  )}
                </div>
              </div>
            );
          })}
        </div>
        {error && (
          <div className="mx-auto mt-4 max-w-3xl rounded-lg border border-destructive/40 bg-destructive/10 px-3 py-2 text-sm text-destructive">
            {error}
          </div>
        )}
      </div>

      <div className="border-t border-border p-3">
        {attachments.length > 0 && (
          <div className="mx-auto mb-2 flex max-w-3xl flex-wrap gap-2">
            {attachments.map((a) => (
              <div
                key={a.id}
                className="flex items-center gap-1.5 rounded-full border border-border bg-muted px-2.5 py-1 text-xs"
              >
                {a.kind === "image" && a.previewUrl ? (
                  <img src={a.previewUrl} alt="" className="h-4 w-4 rounded object-cover" />
                ) : (
                  <FileText size={12} />
                )}
                <span className="max-w-[10rem] truncate">{a.name}</span>
                <button
                  type="button"
                  onClick={() => removeAttachment(a.id)}
                  aria-label="Retirer la pièce jointe"
                >
                  <X size={12} />
                </button>
              </div>
            ))}
          </div>
        )}
        <div className="mx-auto flex max-w-3xl items-end gap-2">
          <input
            ref={imageInputRef}
            type="file"
            accept="image/*"
            multiple
            className="hidden"
            onChange={(e) => {
              Array.from(e.target.files ?? []).forEach((f) => void attachImage(f));
              e.target.value = "";
            }}
          />
          <input
            ref={fileInputRef}
            type="file"
            multiple
            className="hidden"
            onChange={(e) => {
              Array.from(e.target.files ?? []).forEach((f) => void attachFile(f));
              e.target.value = "";
            }}
          />
          <input
            ref={folderInputRef}
            type="file"
            // @ts-expect-error non-standard but supported by every real browser
            webkitdirectory=""
            multiple
            className="hidden"
            onChange={(e) => {
              Array.from(e.target.files ?? []).forEach((f) => void attachFile(f));
              e.target.value = "";
            }}
          />
          <button
            type="button"
            title="Joindre une image"
            className="rounded-lg border border-border p-2 hover:bg-muted disabled:opacity-40"
            disabled={!connected}
            onClick={() => imageInputRef.current?.click()}
          >
            <ImagePlus size={18} />
          </button>
          <button
            type="button"
            title="Joindre un document"
            className="rounded-lg border border-border p-2 hover:bg-muted disabled:opacity-40"
            disabled={!connected}
            onClick={() => fileInputRef.current?.click()}
          >
            <FileText size={18} />
          </button>
          <button
            type="button"
            title="Importer un dossier"
            className="rounded-lg border border-border p-2 hover:bg-muted disabled:opacity-40"
            disabled={!connected}
            onClick={() => folderInputRef.current?.click()}
          >
            <FolderUp size={18} />
          </button>
          <textarea
            value={draft}
            onChange={(e) => setDraft(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Enter" && !e.shiftKey) {
                e.preventDefault();
                handleSend();
              }
            }}
            placeholder={connected ? "Écris un message…" : "Connexion…"}
            disabled={!connected}
            rows={1}
            className="max-h-40 flex-1 resize-none rounded-lg border border-border bg-background px-3 py-2 text-sm outline-none focus:ring-1 focus:ring-primary disabled:opacity-50"
          />
          <button
            type="button"
            title="Envoyer"
            className="rounded-lg bg-primary p-2 text-primary-foreground disabled:opacity-40"
            disabled={!connected || sending || (!draft.trim() && attachments.length === 0)}
            onClick={handleSend}
          >
            <Send size={18} />
          </button>
        </div>
      </div>
    </div>
  );
}

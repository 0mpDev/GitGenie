"use client";

import { Streamdown } from "streamdown";

import "./chat-markdown.css";

export function ChatMarkdown({
  content,
  isStreaming = false,
}: {
  content: string;
  isStreaming?: boolean;
}) {
  return (
    <Streamdown
      className="chat-markdown text-sm leading-relaxed"
      mode={isStreaming ? "streaming" : "static"}
      isAnimating={isStreaming}
    >
      {content}
    </Streamdown>
  );
}
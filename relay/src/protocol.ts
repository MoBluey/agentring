import type { components } from "./generated/api.js";

export type Schemas = components["schemas"];
export type Event = Schemas["Event"];
export type EventType = Event["type"];
export type Chat = Schemas["Chat"];
export type Attachment = Schemas["Attachment"];
export type Message = Schemas["Message"];
export type Device = Schemas["Device"];
export type Party = Schemas["Party"];
export type PushConfig = Schemas["PushConfig"];

type DistributiveOmit<T, K extends PropertyKey> = T extends unknown ? Omit<T, K> : never;

/** An event before the relay stamps `id`, `ts` and `seq` on it. */
export type EventDraft = DistributiveOmit<Event, "id" | "ts" | "seq">;

/** Event types that are never persisted and carry no `seq`. */
export const EPHEMERAL_TYPES: ReadonlySet<EventType> = new Set(["agent.presence", "ws.hello"]);

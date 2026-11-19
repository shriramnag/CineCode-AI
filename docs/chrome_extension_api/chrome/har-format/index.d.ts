declare global {
    interface HARFormatEntry {
        [key: string]: unknown;
    }
    interface HARFormatLog {
        version: string;
        entries: HARFormatEntry[];
        [key: string]: unknown;
    }
}
export {};

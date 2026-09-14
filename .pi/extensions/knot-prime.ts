import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";

const PRIME_TIMEOUT_MS = 5_000;
const READY_TICKET_LIMIT = 10;

export default function knotPrimeExtension(pi: ExtensionAPI) {
	pi.on("before_agent_start", async (event, ctx) => {
		try {
			const result = await pi.exec("knot", ["prime", "--limit", String(READY_TICKET_LIMIT)], {
				cwd: ctx.cwd,
				timeout: PRIME_TIMEOUT_MS,
			});

			if (result.code !== 0) {
				const reason = result.stderr.trim().split("\n", 1)[0] || `exit code ${result.code}`;
				if (ctx.hasUI) ctx.ui.notify(`knot prime failed: ${reason}`, "warning");
				return;
			}

			const prime = result.stdout.trim();
			if (!prime) return;

			return { systemPrompt: `${event.systemPrompt}\n\n${prime}` };
		} catch (error) {
			const reason = error instanceof Error ? error.message : String(error);
			if (ctx.hasUI) ctx.ui.notify(`knot prime failed: ${reason}`, "warning");
			return;
		}
	});
}


import type { ExtensionAPI } from "@mariozechner/pi-coding-agent";

const PRIME_MESSAGE_TYPE = "knot-prime-context";
const PRIME_TIMEOUT_MS = 10_000;

type MaybePrimeMessage = {
	role?: string;
	customType?: string;
};

function isPrimeMessage(message: MaybePrimeMessage): boolean {
	return message.role === "custom" && message.customType === PRIME_MESSAGE_TYPE;
}

export default function knotPrimeExtension(pi: ExtensionAPI) {
	let pendingPrimeOutput: string | undefined;

	pi.on("session_start", async (event, ctx) => {
		const result = await pi.exec("knot", ["prime"], {
			cwd: ctx.cwd,
			timeout: PRIME_TIMEOUT_MS,
		});

		if (result.code !== 0) {
			pendingPrimeOutput = undefined;
			const error = (result.stderr || result.stdout || `exit ${result.code}`).trim();
			ctx.ui.notify(`knot prime failed on ${event.reason}: ${error}`, "warning");
			return;
		}

		const output = result.stdout.trim();
		pendingPrimeOutput = output || undefined;

		if (pendingPrimeOutput) {
			ctx.ui.notify(`Loaded knot prime context on ${event.reason}`, "info");
		}
	});

	pi.on("before_agent_start", async () => {
		if (!pendingPrimeOutput) {
			return;
		}

		const content = pendingPrimeOutput;
		pendingPrimeOutput = undefined;

		return {
			message: {
				customType: PRIME_MESSAGE_TYPE,
				display: false,
				content: `Context from \`knot prime\` captured at session start:\n\n${content}`,
			},
		};
	});

	pi.on("context", async (event) => {
		let lastPrimeIndex = -1;

		for (let i = event.messages.length - 1; i >= 0; i -= 1) {
			if (isPrimeMessage(event.messages[i] as MaybePrimeMessage)) {
				lastPrimeIndex = i;
				break;
			}
		}

		if (lastPrimeIndex === -1) {
			return;
		}

		return {
			messages: event.messages.filter(
				(message, index) => !isPrimeMessage(message as MaybePrimeMessage) || index === lastPrimeIndex,
			),
		};
	});
}

<?php
declare(strict_types=1);
namespace ControlCenter;

/** Provider explicitly rejected link creation before creating a payable link.
 * Network timeouts/500/malformed replies MUST NOT use this exception. */
final class RampexLinkRejected extends \RuntimeException {}

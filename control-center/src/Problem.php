<?php
declare(strict_types=1);
namespace ControlCenter;

final class Problem extends \RuntimeException
{
    public function __construct(public readonly int $status, string $message)
    {
        parent::__construct($message);
    }
}

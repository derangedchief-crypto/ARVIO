<?php
declare(strict_types=1);
namespace ControlCenter;

final class Input
{
    public static function keys(array $input, array $allowed): void
    {
        if (array_diff(array_keys($input), $allowed)) throw new Problem(422, 'Unsupported fields in request.');
    }
    public static function text(mixed $value, string $name, int $max, bool $required = true): string
    {
        if (!is_string($value) || !mb_check_encoding($value, 'UTF-8')) throw new Problem(422, "Invalid $name.");
        $value = trim($value);
        if (($required && $value === '') || mb_strlen($value) > $max || preg_match('/[\x00-\x08\x0B\x0C\x0E-\x1F\x7F]/', $value)) {
            throw new Problem(422, "Invalid $name.");
        }
        return $value;
    }
    public static function id(mixed $value): string
    {
        if (!is_string($value) || !preg_match('/^[a-f0-9]{32}$/D', $value)) throw new Problem(404, 'Record not found.');
        return $value;
    }
    public static function email(mixed $value): string
    {
        $email = strtolower(self::text($value, 'email', 254));
        if (!filter_var($email, FILTER_VALIDATE_EMAIL)) throw new Problem(422, 'Invalid email.');
        return $email;
    }
    public static function password(mixed $value): string
    {
        if (!is_string($value) || !mb_check_encoding($value, 'UTF-8') || mb_strlen($value) < 14 || strlen($value) > 72 || preg_match('/[\x00-\x1F\x7F]/', $value)) {
            throw new Problem(422, 'Use a password of at least 14 characters (maximum 72 bytes).');
        }
        return $value;
    }
    public static function integer(mixed $value, int $min, int $max): int
    {
        if (!is_int($value) || $value < $min || $value > $max) throw new Problem(422, 'Invalid numeric limit.');
        return $value;
    }
    public static function page(mixed $value): int
    {
        if (!is_scalar($value) || !preg_match('/^[1-9][0-9]{0,4}$/D', (string) $value)) throw new Problem(422, 'Invalid page.');
        return (int) $value;
    }
}

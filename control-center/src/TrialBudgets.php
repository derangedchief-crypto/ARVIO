<?php
declare(strict_types=1);
namespace ControlCenter;

final class TrialBudgets
{
    public function __construct(private readonly Database $db,private readonly Config $config) {}
    /** Acquire BEFORE the transaction and release AFTER commit, not after insertion. */
    public function locked(array $session,callable $work):mixed {
        if($this->db->pdo->inTransaction())throw new \LogicException('Trial budget lock must precede its transaction.');
        $name='trial-ip-'.substr($session['ip_ref'],0,40);$locked=(int)$this->db->query('SELECT GET_LOCK(?,5)',[$name])->fetchColumn();if($locked!==1)throw new Problem(429,'Trial service is busy; retry this same request.');
        try{return $work();}finally{$this->db->query('SELECT RELEASE_LOCK(?)',[$name]);}
    }
    /** Caller holds both IP advisory lock and tenant row lock inside its transaction. */
    public function reserve(array $operation,array $offer,array $session):void {
            $name='trial-ip-'.substr($session['ip_ref'],0,40);
            if(!$this->db->pdo->inTransaction()||(int)$this->db->query('SELECT IS_USED_LOCK(?)=CONNECTION_ID()',[$name])->fetchColumn()!==1)throw new \LogicException('Missing trial quota transaction lock.');
            $reseller=$offer['panel_reseller_id'];$device=$session['device_ref'];$ip=$session['ip_ref'];
            $daily=(int)$this->db->query('SELECT COUNT(*) FROM trial_signup_budgets WHERE panel_reseller_id=? AND counted=1 AND created_at>=UTC_DATE()',[$reseller])->fetchColumn();
            $deviceCount=(int)$this->db->query('SELECT COUNT(*) FROM trial_signup_budgets WHERE panel_reseller_id=? AND device_ref=? AND counted=1 AND created_at>=UTC_DATE()',[$reseller,$device])->fetchColumn();
            $ipCount=(int)$this->db->query('SELECT COUNT(*) FROM trial_signup_budgets WHERE ip_ref=? AND counted=1 AND created_at>=DATE_SUB(UTC_TIMESTAMP(),INTERVAL 1 HOUR)',[$ip])->fetchColumn();
            if($daily>=$offer['daily_limit']||$deviceCount>=$this->config->number('trial_device_daily_limit',10,1,100)||$ipCount>=$this->config->number('trial_ip_hourly_limit',50,1,1000))throw new Problem(429,'No automatic trial capacity is available for this request right now.');
            $this->db->query('INSERT INTO trial_signup_budgets (operation_id,panel_reseller_id,device_ref,ip_ref) VALUES (?,?,?,?)',[$operation['id'],$reseller,$device,$ip]);
    }
}

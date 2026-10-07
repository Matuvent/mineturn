execute store result score @s mte_range run mineturn ai target nearest_enemy
execute if score @s mte_range matches 0 run return run mineturn ai wait
execute store result score @s mte_range run mineturn ai query in_attack_range
execute if score @s mte_range matches 0 run return run function mineturn_example:zombie/chase
return run mineturn ai use mineturn:claw

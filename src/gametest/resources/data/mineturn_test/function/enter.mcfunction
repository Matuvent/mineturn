tag @s add mt_entered
$scoreboard players set @s mt_param $(mark)
mineturn ai target nearest_enemy
mineturn ai schedule 25 mineturn_test:timer

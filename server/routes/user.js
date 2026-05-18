const express = require("express");
const router = express.Router();

router.post("/login", (req, res) => {
    try {
        const db = req.db;
        const { username, password} = req.body;

        const query = `
        SELECT * FROM users 
        WHERE username = '${username}' AND password = '${password}'
        `;

        db.get(query, (err, row) => {
            if (err){
            return res.json({ success: false, error: err.message});
            }

            if (row) {
                if (username == 'adminUser2'){
                    return res.json({success: true, message: "Congrats: FLAG{adm1n_l0g1n_succ4s}"});
                } else {
                    res.json({
                        success: true,
                        message: "Login successful",
                    });
                }
            } else {
                res.json({
                    success: false,
                    error: "Invalid credentials",
                });
            }
        });

    } catch (err) {
        return res.json({ success: false, error: err.message});
    }
});


router.post("/register", (req, res) => {
    try{
        const db = req.db;
        const { username, password, salt} = req.body;

        //console.log("Received a register request. Payload: \n {"+username+", "+password+", "+salt+"}\n");

        const query_check = `
        SELECT * FROM users 
        WHERE username = '${username}'
        `;

        db.get(query_check, (err, row) => {
            if (err){
            return res.json({ success: false, error: err.message});
            }

            if (row) {
            return res.json({
                success: false,
                error: "username exists already",
            });
            } else {
                // Add user to the db
                const query_add = `
                INSERT INTO users (username, password, salt)
                VALUES ('${username}', '${password}', '${salt}')
                `;

                db.run(query_add, (err) => {
                if (err){
                    return res.json({ success: false, error: err.message});
                }
                res.json({success: true, message: `User ${username} added succesfully`});
                });
            }
        });

        

    } catch (error){
        return res.json({ success: false, error: err.message});
    }
});


router.get("/get_salt", (req, res) => {
    try {
        const db = req.db;
        const {username} = req.query;
        //console.log("Received a get_salt request. Payload: \n" + username + "\n");

        const query = `
        SELECT * FROM users 
        WHERE username = '${username}'
        `;

        db.get(query, (err, row) => {
            if (err){
            return res.json({ success: false, error: err.message});
            }

            if (row) {
            res.json({
                success: true,
                message: row.salt,
            });
            } else {
                res.json({
                    success: false,
                    error: "Invalid user",
                });
            }
        });
    } catch (err) {
        return res.json({ success: false, error: err.message});
    }
});


module.exports = router;